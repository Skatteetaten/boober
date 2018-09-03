package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.AuroraMetrics
import no.skatteetaten.aurora.boober.utils.LambdaOutputStream
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.EmtpyCommitException
import org.eclipse.jgit.lib.BranchTrackingStatus
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.TextProgressMonitor
import org.eclipse.jgit.revwalk.RevTag
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.io.File
import java.io.PrintWriter

@Configuration
class GitServices(
    val userDetails: UserDetailsProvider,
    val metrics: AuroraMetrics
) {

    enum class Domain {
        AURORA_CONFIG,
        VAULT
    }

    @Target(AnnotationTarget.TYPE, AnnotationTarget.FUNCTION, AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
    @Retention(AnnotationRetention.RUNTIME)
    @Qualifier
    annotation class TargetDomain(val value: Domain)

    @Bean
    @TargetDomain(Domain.AURORA_CONFIG)
    @Primary
    fun auroraConfigGitService(
        @Value("\${boober.git.urlPattern}") url: String,
        @Value("\${boober.git.checkoutPath}") checkoutPath: String,
        @Value("\${boober.git.username}") username: String,
        @Value("\${boober.git.password}") password: String
    ): GitService {
        return GitService(userDetails, url, checkoutPath, username, password, metrics)
    }

    @Bean
    @TargetDomain(Domain.VAULT)
    fun vaultGitService(
        @Value("\${vault.git.urlPattern}") url: String,
        @Value("\${vault.git.checkoutPath}") checkoutPath: String,
        @Value("\${vault.git.username}") username: String,
        @Value("\${vault.git.password}") password: String
    ): GitService {
        return GitService(userDetails, url, checkoutPath, username, password, metrics)
    }
}

open class GitService(
    val userDetails: UserDetailsProvider,
    val url: String,
    val checkoutPath: String,
    val username: String,
    val password: String,
    val metrics: AuroraMetrics
) {

    val logger: Logger = LoggerFactory.getLogger(GitService::class.java)

    val cp = UsernamePasswordCredentialsProvider(username, password)

    @JvmOverloads
    fun checkoutRepository(repositoryName: String, refName: String, checkoutFolder: String = repositoryName, deleteUnpushedCommits: Boolean = true): Git {
        val repoPath = File(File("$checkoutPath/$checkoutFolder").absoluteFile.absolutePath)
        val git: Git? = repoPath.takeIf(File::exists).let {
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

        return git ?: cloneAndCheckout(repositoryName, repoPath, refName, deleteUnpushedCommits)
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

    private fun findRef(git: Git, refName: String): Ref {
        val ref = git.repository.findRef(refName)

        return ref ?: git.repository.findRef("origin/$refName")
        ?: throw GitReferenceException("No git reference with refName=$refName")
    }

    private fun cloneAndCheckout(repositoryName: String, repoPath: File, refName: String, deleteUnpushedCommits: Boolean): Git {
        cloneRepository(repositoryName, repoPath)
        return updateRepository(repoPath, deleteUnpushedCommits, refName)
    }

    private fun cloneRepository(repositoryName: String, repoPath: File): Git {
        return metrics.withMetrics("git_checkout") {
            val dir = repoPath.apply { mkdirs() }
            val uri = url.format(repositoryName)

            try {
                Git.cloneRepository()
                        .setURI(uri)
                        .setCredentialsProvider(cp)
                        .setDirectory(dir)
                        .call()
            } catch (ex: Exception) {
                dir.deleteRecursively()
                throw ex
            }
        }
    }

    fun commitAndPushChanges(repo: Git, commitMessage: String? = null) {

        repo.add().addFilepattern(".").call()
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
                .call()
            repo.push()
                .setCredentialsProvider(cp)
                .add("refs/heads/master")
                .call()
        } catch (e: EmtpyCommitException) {
            // Ignore empty commits. It's ok.
        } catch (e: Exception) {
            throw e
        }
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
