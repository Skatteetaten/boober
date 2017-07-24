package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.model.AuroraGitFile
import no.skatteetaten.aurora.boober.service.internal.ApplicationResult
import no.skatteetaten.aurora.boober.service.internal.GitException
import no.skatteetaten.aurora.boober.utils.use
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.EmtpyCommitException
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.api.errors.NoHeadException
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File
import java.io.FileWriter
import java.util.*

@Service
class GitService(
        val userDetails: UserDetailsProvider,
        @Value("\${boober.git.urlPattern}") val url: String,
        @Value("\${boober.git.checkoutPath}") val checkoutPath: String,
        @Value("\${boober.git.username}") val username: String,
        @Value("\${boober.git.password}") val password: String) {

    val logger: Logger = LoggerFactory.getLogger(GitService::class.java)

    val cp = UsernamePasswordCredentialsProvider(username, password)

    fun checkoutRepoForAffiliation(affiliation: String): Git {

        val dir = File("$checkoutPath/${UUID.randomUUID()}").apply { mkdirs() }
        val uri = url.format(affiliation)

        return try {
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

    fun deleteDirectory(git: Git, dirName: String) {
        try {
            git.rm().addFilepattern(dirName).call()

            val status = git.status().call()
            commitAllChanges(git, "Added: ${status.added.size}, Modified: ${status.changed.size}, Deleted: ${status.removed.size}")
            push(git)
        } catch(ex: EmtpyCommitException) {
        } catch(ex: GitAPIException) {
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
        } catch(ex: EmtpyCommitException) {
        } catch(ex: GitAPIException) {
            throw GitException("Unexpected error committing changes", ex)
        } finally {
            closeRepository(git)
        }
    }

    fun closeRepository(repo: Git) {
        File(repo.repository.directory.parent).deleteRecursively()
        repo.close()
    }

    fun getAllFilesInRepo(git: Git): Map<String, Pair<RevCommit?, File>> {
        val folder = git.repository.directory.parentFile
        return folder.walkBottomUp()
                .onEnter { !it.name.startsWith(".git") }
                .filter { it.isFile }
                .associate {
                    val path = it.relativeTo(folder).path
                    val commit = try {
                         git.log().addPath(path).setMaxCount(1).call().firstOrNull()
                    } catch(e: NoHeadException) {
                        logger.debug("No history was found for path={}", path)
                        null
                    }
                    path to Pair(commit, it)
                }
    }

    fun getAllFilesInRepoList(git: Git): List<AuroraGitFile> {
        val folder = git.repository.directory.parentFile
        return folder.walkBottomUp()
                .onEnter { !it.name.startsWith(".git") }
                .filter { it.isFile }
                .map {
                    val path = it.relativeTo(folder).path
                    val commit = try {
                        git.log().addPath(path).setMaxCount(1).call().firstOrNull()
                    } catch(e: NoHeadException) {
                        logger.debug("No history was found for path={}", path)
                        null
                    }
                    AuroraGitFile(path, it, commit)
                }.toList()
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

        getAllFilesInRepo(git)
                .map { it.key }
                .filter { keep.invoke(it) }
                .filter { !files.contains(it) }
                .forEach { git.rm().addFilepattern(it).call() }
    }

    private fun commitAllChanges(git: Git, message: String): RevCommit {

        val user = userDetails.getAuthenticatedUser().let { PersonIdent(it.fullName, "${it.username}@skatteetaten.no") }
        return git.commit()
                .setAll(true)
                .setAllowEmpty(false)
                .setAuthor(user)
                .setMessage(message)
                .call()
    }

    private fun createBranch(git: Git, branchName: String, commit: RevCommit): Ref {

        return git.branchCreate()
                .setForce(true)
                .setName(branchName)
                .setStartPoint(commit)
                .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.SET_UPSTREAM)
                .call()
    }

    fun push(git: Git) {

        git.push()
                .setCredentialsProvider(cp)
                .setPushAll()
                .setPushTags()
                .call()
    }

    /*
    dette er Det vi skal gjøre når det blir kjørt en setup kommando. resourceVersion får vi først etter at kommando er kjørt
    så dette må gjøres etter at vi har installert objektene. Det er resourceVersion i DC vi hovedsakelig bryr oss om.
    men hva med de tilfellene hvor vi ikke endrer dc men f.eks bare endrer en configMap? Da vil vi jo ikke ha resourceVersion på dc være endret.
    må vi faktisk ha med en annotated tag for hver ressurstype vi endrer?

    så f.eks hvis en boober setup endrer en configMap så må vi hente ned resourceVersion etterpå og tagge med namespace-name-resourcetype-resourceVersion?
    Vi har jo sagt at dette apiet kun skal applye det som faktisk er endret. så hvis vi applyer en configmap og den ikke endret så får vi vel samme resourceVersion og da skal jo
    ikke denne taggen flyttes?
     */
     fun markRelease(git: Git, tag:String, tagBody:String) {

        //createBranch(git, "$namespace-$name", commit)

        git.tag().setAnnotated(true).setName(tag).setMessage(tagBody).call()
    }
}
