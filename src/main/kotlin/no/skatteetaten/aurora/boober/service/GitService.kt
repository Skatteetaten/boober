package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.AuroraMetrics
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.model.AuroraSecretFile
import no.skatteetaten.aurora.boober.utils.use
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.EmtpyCommitException
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.api.errors.NoHeadException
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevTag
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File
import java.io.FileWriter

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

    fun checkoutRepoForAffiliation(affiliation: String): Git {
        synchronized(affiliation, {
            val repoPath = File("$checkoutPath/$affiliation")
            if (repoPath.exists()) {
                val git = Git.open(repoPath)
                ///TODO reset hard head? Is it an error if this is dirty?
                //TODO:Error handling
                /*
                 git.pull()
                         .setRebase(true)
                         .setRemote("origin")
                         .setRemoteBranchName("master")
                         .setCredentialsProvider(cp)
                         .call()*/
                return git
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

    fun deleteDirectory(git: Git, dirName: String) {
        try {
            git.rm().addFilepattern(dirName).call()

            val status = git.status().call()
            commitAllChanges(git, "Added: ${status.added.size}, Modified: ${status.changed.size}, Deleted: ${status.removed.size}")
            push(git)
        } catch (ex: EmtpyCommitException) {
            throw AuroraConfigException("No such directory")
        } catch (ex: GitAPIException) {
            throw GitException("Unexpected error committing changes", ex)
        } finally {
            closeRepository(git)
        }
    }

    fun saveFilesAndClose(git: Git, files: Map<String, String>, keep: (String) -> Boolean) {
        try {
            writeAndAddChanges(git, files)
            deleteMissingFiles(git, files.keys, keep)

            val status = git.status().call()
            commitAllChanges(git, "Added: ${status.added.size}, Modified: ${status.changed.size}, Deleted: ${status.removed.size}")
            push(git)
        } catch (ex: EmtpyCommitException) {
        } catch (ex: GitAPIException) {
            throw GitException("Unexpected error committing changes", ex)
        } finally {
            closeRepository(git)
        }
    }

    @JvmOverloads
    fun closeRepository(repo: Git, deleteFiles: Boolean = false) {
        if (deleteFiles) {
            File(repo.repository.directory.parent).deleteRecursively()
        }
        repo.close()
    }

    fun getAllFiles(git: Git): Map<String, File> {

        val folder = git.repository.directory.parentFile
        logger.debug("Get all files")
        val files = folder.walkBottomUp()
                .onEnter { !it.name.startsWith(".git") }
                .filter { it.isFile }
                .associate {
                    it.relativeTo(folder).path to it
                }
        logger.debug("/Get all files")
        return files
    }

    fun getAllAuroraConfigFiles(git: Git): Map<String, File> {
        return getAllFiles(git)
                .filter { !it.key.startsWith(GIT_SECRET_FOLDER) }
    }

    fun getAllFilesInRepo(git: Git): Map<String, Pair<RevCommit?, File>> {
        val files = getAllAuroraConfigFiles(git).mapValues {
            Pair(getRevCommit(git, it.key), it.value)
        }
        return files
    }

    fun getAllSecretFilesInRepoList(git: Git): List<AuroraSecretFile> {
        return getAllFiles(git)
                .filter { it.key.startsWith(GIT_SECRET_FOLDER) }
                .map {
                    AuroraSecretFile(it.key, it.value, getRevCommit(git, it.key))
                }
    }

    private fun getRevCommit(git: Git, path: String?): RevCommit? {
        val commit = try {
            git.log().addPath(path).setMaxCount(1).call().firstOrNull()
        } catch (e: NoHeadException) {
            logger.debug("No history was found for path={}", path)
            null
        }
        return commit
    }

    private fun writeAndAddChanges(git: Git, files: Map<String, String>) {

        files.forEach { (fileName, value) ->
            fileName.split("/")
                    .takeIf { it.size >= 2 }
                    ?.let {
                        val subFolder = it.dropLast(1).joinToString("/")
                        File(git.repository.directory.parent, subFolder).mkdirs()
                    }

            val file = File(git.repository.directory.parent, fileName).apply { createNewFile() }

            FileWriter(file, false).use { it.write(value) }

            git.add().addFilepattern(fileName).call()
        }
    }

    private fun deleteMissingFiles(git: Git, files: Set<String>, keep: (String) -> Boolean) {

        logger.debug("delete files")
        //TODO: If this takes time rewrite to not include File content
        getAllFiles(git)
                .map { it.key }
                .filter { keep.invoke(it) }
                .filter { !files.contains(it) }
                .forEach { git.rm().addFilepattern(it).call() }

        logger.debug("/delete files")
    }

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

    fun push(git: Git) {

        metrics.withMetrics("git_push", {
            git.push()
                    .setCredentialsProvider(cp)
                    .setPushAll()
                    .setPushTags()
                    .call()
        })
    }

    fun markRelease(git: Git, tag: String, tagBody: String) {
        val user = userDetails.getAuthenticatedUser().let { PersonIdent(it.fullName ?: it.username, "${it.username}@skatteetaten.no") }
        git.tag().setTagger(user).setAnnotated(true).setName(tag).setMessage(tagBody).call()
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
}
