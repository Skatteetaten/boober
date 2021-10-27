package no.skatteetaten.aurora.boober.service

import mu.KotlinLogging
import no.skatteetaten.aurora.AuroraMetrics
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.EmptyCommitException
import org.eclipse.jgit.lib.BranchTrackingStatus
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.revwalk.RevTag
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

open class GitService(
    val userDetails: UserDetailsProvider,
    val urlPattern: String,
    val checkoutPath: String,
    val username: String,
    val password: String,
    val metrics: AuroraMetrics
) {

    val cp = UsernamePasswordCredentialsProvider(username, password)

    val locks = ConcurrentHashMap<String, Any>()

    fun checkoutRepository(
        repositoryName: String,
        refName: String,
        checkoutFolder: String = repositoryName,
        deleteUnpushedCommits: Boolean = true
    ): Git {
        val lock = locks.computeIfAbsent(repositoryName) { object {} }
        return synchronized(lock) {
            val repoPath = File(File("$checkoutPath/$checkoutFolder").absoluteFile.absolutePath)
            val git: Git? = repoPath.takeIf(File::exists)?.let {
                try {
                    updateRepository(repoPath, deleteUnpushedCommits, refName)
                } catch (e: GitReferenceException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("Local repository update failed. Cause: ${e.message}.")
                    repoPath.deleteRecursively()
                    null
                }
            }

            git ?: cloneAndCheckout(repositoryName, repoPath, refName, deleteUnpushedCommits)
        }
    }

    private fun updateRepository(repoPath: File, failOnUnpushedCommits: Boolean, refName: String): Git {

        val git = Git.open(repoPath)
        git.fetch()
            .setCredentialsProvider(cp)
            .call()

        if (git.repository.allRefs.isEmpty()) return git

        val ref = findRef(git, refName)
        val branchList = git.branchList().call()

        if (branchList.contains(ref) || ref.name.contains("refs/tags/")) {
            git.checkout()
                .setCreateBranch(false)
                .setName(refName)
                .call()
        } else {
            git.checkout()
                .setCreateBranch(true)
                .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                .setName(refName)
                .setStartPoint(ref.name)
                .call()
        }

        if (ref.name.startsWith("refs/heads/") || ref.name.startsWith("refs/remotes/")) {
            git.pull()
                .setRebase(true)
                .setRemote("origin")
                .setCredentialsProvider(cp)
                .call()
            if (failOnUnpushedCommits) {
                val trackingStatus = BranchTrackingStatus.of(git.repository, git.repository.fullBranch)
                if (trackingStatus.aheadCount != 0 || trackingStatus.behindCount != 0) {
                    throw IllegalStateException("We are ${trackingStatus.aheadCount} commit(s) ahead and ${trackingStatus.behindCount} behind ${trackingStatus.remoteTrackingBranch}")
                }
            }
        }

        return git
    }

    fun findRef(git: Git, refName: String): Ref {
        val ref = git.repository.findRef(refName)

        return ref ?: git.repository.findRef("origin/$refName")
            ?: throw GitReferenceException("No git reference with refName=$refName")
    }

    private fun cloneAndCheckout(
        repositoryName: String,
        repoPath: File,
        refName: String,
        deleteUnpushedCommits: Boolean
    ): Git {
        cloneRepository(repositoryName, repoPath)
        return updateRepository(repoPath, deleteUnpushedCommits, refName)
    }

    private fun cloneRepository(repositoryName: String, repoPath: File): Git {
        return metrics.withMetrics("git_checkout") {
            val dir = repoPath.apply { mkdirs() }
            val uri = urlPattern.format(repositoryName)

            val url = if (uri.startsWith("build")) {
                File(uri).absoluteFile.absolutePath
            } else uri

            try {
                Git.cloneRepository()
                    .setURI(url)
                    .setCredentialsProvider(cp)
                    .setDirectory(dir)
                    .call()
            } catch (ex: Exception) {
                dir.deleteRecursively()
                throw ex
            }
        }
    }

    /**
     * When using addFilePattern or rmFilePattern there might be some changes that will not be staged and committed.
     * rmFilePattern will not delete files only stage them.
     * For removing unwanted changes use [cleanRepo].
     */
    fun commitAndPushChanges(
        repo: Git,
        ref: String = "refs/heads/master",
        commitMessage: String? = null,
        addFilePattern: String? = ".",
        rmFilePattern: String? = null
    ) {

        if (addFilePattern != null) {
            repo.add().addFilepattern(addFilePattern).call()
        }
        if (rmFilePattern != null) {
            repo.rm().setCached(true).addFilepattern(rmFilePattern).call()
        }
        val status = repo.status().call()
        val message = commitMessage
            ?: "Added: ${status.added.size}, Modified: ${status.changed.size}, Deleted: ${status.removed.size}"
        val authorIdent = getPersonIdentFromUserDetails()
        try {
            repo.commit()
                .setAll(true)
                .setAllowEmpty(false)
                .setAuthor(authorIdent)
                .setMessage(message)
                .setSign(false) // jgit leser lokal .gitconfig og her har jeg satt sign=true.. grr. liker ikke jgit.
                .call()
            repo.push()
                .setCredentialsProvider(cp)
                .add(ref)
                .call()
        } catch (e: EmptyCommitException) {
            // Ignore empty commits. It's ok.
        } catch (e: Exception) {
            throw e
        }
    }

    /**
     * This should be used to prevent unwanted changes from being committed.
     * @param repo Git repository to preform clean operation
     * @param removeUnstaged will remove changed files
     * @param removeUntracked will remove new files
     * @return true if clean up was successfully, false if not.
     */
    fun cleanRepo(repo: Git, removeUnstaged: Boolean = true, removeUntracked: Boolean = true): Boolean {
        val status = repo.status().call()
        if (status.isClean) {
            return true
        }

        // Changed files
        if (removeUnstaged) {
            repo.checkout().addPath(".").call()
        }

        // New files
        if (removeUntracked) {
            repo.clean().setPaths(setOf(".")).setCleanDirectories(true).call()
        }

        val statusAfterCheckout = repo.status().call()

        return statusAfterCheckout.isClean
    }

    fun getTagHistory(git: Git): List<RevTag> {
        val tags = git.tagList().call()
        return tags.mapNotNull {
            val objectLoader = git.repository.open(it.objectId)
            val bytes = objectLoader.getCachedBytes(Int.MAX_VALUE)
            RevTag.parse(bytes)
        }
    }

    private fun getPersonIdentFromUserDetails() =
        userDetails.getAuthenticatedUser().let {
            PersonIdent(it.fullName ?: it.username, "${it.username}@skatteetaten.no")
        }
}
