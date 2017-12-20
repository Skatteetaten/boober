package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.AuroraMetrics
import no.skatteetaten.aurora.boober.model.AuroraSecretFile
import no.skatteetaten.aurora.boober.utils.LambdaOutputStream
import no.skatteetaten.aurora.boober.utils.use
import org.apache.commons.io.FileUtils
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.api.errors.NoHeadException
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.TextProgressMonitor
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevTag
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.nio.charset.Charset

@Service
class GitService(
        val userDetails: UserDetailsProvider,
        @Value("\${boober.git.urlPattern}") val url: String,
        @Value("\${boober.git.checkoutPath}") val checkoutPath: String,
        @Value("\${boober.git.username}") val username: String,
        @Value("\${boober.git.password}") val password: String,
        val metrics: AuroraMetrics) {

    val GIT_SECRET_FOLDER = ".secret"

    val logger: Logger = LoggerFactory.getLogger(GitService::class.java)

    val cp = UsernamePasswordCredentialsProvider(username, password)

    // Try to delete this. Only used in tests.
    fun deleteFiles(affiliation: String) {
        val repoDir = File(checkoutPath + "/" + affiliation)
        if (repoDir.exists()) {
            repoDir.deleteRecursively()
        }
    }

    //only test
    fun openRepo(affiliation: String): Git {
        val repoPath = File("$checkoutPath/$affiliation")
        return Git.open(repoPath)
    }

    fun checkoutRepoForAffiliation(affiliation: String): Git {
        synchronized(affiliation, {
            val repoPath = File("$checkoutPath/$affiliation")
            if (repoPath.exists()) {
                try {
                    val git = Git.open(repoPath)
                    git.pull()
                            .setRebase(true)
                            .setRemote("origin")
                            .setCredentialsProvider(cp)
                            .call()
                    return git
                } catch (e: Exception) {
                    repoPath.deleteRecursively()
                }
            }
            return metrics.withMetrics("git_checkout", {
                val dir = repoPath.apply { mkdirs() }
                val uri = url.format(affiliation)

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
            })
        })
    }

    //support multiple patterns
    fun deleteDirectory(git: Git, dirName: String) {
        try {
            git.rm().addFilepattern(dirName).call()

            val status = git.status().call()
            commitAllChanges(git, "Added: ${status.added.size}, Modified: ${status.changed.size}, Deleted: ${status.removed.size}")
            pushMaster(git)
        } catch (ex: GitAPIException) {
            throw GitException("Unexpected error committing changes", ex)
        } finally {
            closeRepository(git)
        }
    }

/*
    fun saveFilesAndClose(git: Git, files: Map<String, String>, keep: (String) -> Boolean) {
        try {
            logger.debug("write add changes")
            writeAndAddChanges(git, files)
            logger.debug("delete missing files")
            deleteMissingFiles(git, files.keys, keep)
            logger.debug("status")
            val status = git.status().call()
            logger.debug("commit")
            commitAllChanges(git, "Added: ${status.added.size}, Modified: ${status.changed.size}, Deleted: ${status.removed.size}")
            logger.debug("push")
            pushMaster(git)
            logger.debug("/push")
        } catch (ex: EmtpyCommitException) {
        } catch (ex: GitAPIException) {
            throw GitException("Unexpected error committing changes", ex)
        } finally {
            closeRepository(git)
        }
    }
*/

    fun closeRepository(repo: Git) {
        repo.close()
    }


    //TODO: move out to another abstraction
    fun getAllAuroraConfigFiles(git: Git): Map<String, File> {
        return getAllFiles(git)
                .filter { !it.key.startsWith(GIT_SECRET_FOLDER) }
        //TODO: Try to add this to only allow json files in AuroraConfig
//                .filter{ it.key.endsWith(".json")}
    }


    //TODO: Move out to another abstraction
    fun getAllSecretFilesInRepoList(git: Git): List<AuroraSecretFile> {
        return getAllFiles(git)
                .filter { it.key.startsWith(GIT_SECRET_FOLDER) }
                .map {
                    AuroraSecretFile(it.key, it.value, getRevCommit(git, it.key))
                }
    }

    fun getRevCommit(git: Git, path: String?): RevCommit? {
        val commit = try {
            git.log().addPath(path).setMaxCount(1).call().firstOrNull()
        } catch (e: NoHeadException) {
            logger.debug("No history was found for path={}", path)
            null
        }
        return commit
    }


    fun writeAndAddChanges(git: Git, files: Map<String, String>) {

        files.forEach { (fileName, value) ->
            fileName.split("/")
                    .takeIf { it.size >= 2 }
                    ?.let {
                        val subFolder = it.dropLast(1).joinToString("/")
                        File(git.repository.directory.parent, subFolder).mkdirs()
                    }

            val file = File(git.repository.directory.parent, fileName).apply { createNewFile() }

            FileWriter(file, false).use { it.write(value) }

            //It is more optimal to add all filepatterns at the end?
            git.add().addFilepattern(fileName).call()
        }
    }

    private fun writeAndAddChanges2(git: Git, files: Map<String, String>) {

        files.forEach { (fileName, value) ->
            File(git.repository.directory.parent, fileName).apply {
                FileUtils.forceMkdirParent(this)
                FileUtils.write(this, value, Charset.defaultCharset())
            }
        }

        git.add().apply {
            files.keys.forEach { this.addFilepattern(it) }
        }.call()
    }


/*
    private fun deleteMissingFiles(git: Git, files: Set<String>, keep: (String) -> Boolean) {
        //TODO: If this takes time rewrite to not include File content
        getAllFiles(git)
                .map { it.key }
                .filter { keep.invoke(it) }
                .filter { !files.contains(it) }
                .forEach { git.rm().addFilepattern(it).call() }
    }
*/

    private fun commitAllChanges(git: Git, message: String): RevCommit {

        val user = userDetails.getAuthenticatedUser().let {

            PersonIdent(it.fullName ?: it.username, "${it.username}@skatteetaten.no")
        }
        return git.commit()
                .setAll(true)
                .setAllowEmpty(false)
                .setAuthor(user)
                .setMessage(message)
                .call()
    }

    fun pushTags(git: Git, tags: List<Ref>) {

        logger.debug("push tags to git")
        val cmd = git.push()
        logger.debug("added tag")
        tags.forEach { cmd.add(it) }
        logger.debug("/added tag")
        metrics.withMetrics("git_push_tags", {
            cmd.setCredentialsProvider(cp)
            if (logger.isDebugEnabled) {
                cmd.progressMonitor = TextProgressMonitor(PrintWriter(LambdaOutputStream {
                    logger.debug(it)
                }))
            }
            cmd.call()
        })
        logger.debug("/push tags to git")
    }

    fun pushMaster(git: Git) {

        logger.debug("push config")
        metrics.withMetrics("git_push", {
            logger.debug("push config inner")
            val cmd = git.push()
                    .setCredentialsProvider(cp)
                    .add("refs/heads/master")
            if (logger.isDebugEnabled) {
                cmd.progressMonitor = TextProgressMonitor(PrintWriter(LambdaOutputStream {
                    logger.debug(it)
                }))
            }
            cmd.call()
            logger.debug("/push config inner")
        })
        logger.debug("/push config")
    }

    fun markRelease(git: Git, tag: String, tagBody: String): Ref {
        val user = userDetails.getAuthenticatedUser().let { PersonIdent(it.fullName ?: it.username, "${it.username}@skatteetaten.no") }
        return git.tag().setTagger(user).setAnnotated(true).setName(tag).setMessage(tagBody).call()
    }

    fun readTag(git: Git, oid: ObjectId): RevTag? {

        val objectLoader = git.repository.open(oid)

        val bytes = objectLoader.getCachedBytes(Int.MAX_VALUE)

        return RevTag.parse(bytes)

    }

    fun tagHistory(git: Git): List<RevTag> {
        val tags = git.tagList().call()
        return tags.mapNotNull { readTag(git, it.objectId) }
    }


    //TODO: This and the one below must be joined together somehow.
    fun getFile(git: Git, fileName: String): AuroraSecretFile? {

        val folder = git.repository.directory.parentFile
        return folder.walkBottomUp()
                .onEnter { !it.name.startsWith(".git") }
                .filter { it.isFile && it.relativeTo(folder).path == fileName }
                .map {
                    val path = it.relativeTo(folder).path
                    AuroraSecretFile(path, it, getRevCommit(git, path))
                }.firstOrNull()
    }


    fun getAllFiles(git: Git): Map<String, File> {

        val folder = git.repository.directory.parentFile
        val files = folder.walkBottomUp()
                //TODO: Ignore all files starting with . not just .git
                .onEnter { !it.name.startsWith(".git") }
                .filter { it.isFile }
                .associate {
                    it.relativeTo(folder).path to it
                }
        return files
    }

}
