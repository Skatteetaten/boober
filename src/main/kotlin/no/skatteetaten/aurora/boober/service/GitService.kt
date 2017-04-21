package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.ObjectMapper
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.utils.use
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File
import java.io.FileWriter
import java.util.*

@Service
class GitService(
        val mapper: ObjectMapper,
        val userDetails: UserDetailsProvider,
        @Value("\${boober.git.url}") val url: String,
        @Value("\${boober.git.checkoutPath}") val checkoutPath: String,
        @Value("\${boober.git.username}") val username: String,
        @Value("\${boober.git.password}") val password: String) {

    val cp = UsernamePasswordCredentialsProvider(username, password)

    fun checkoutRepoForAffiliation(affiliation: String): Git {

        val dir = File("$checkoutPath/${UUID.randomUUID()}").apply { mkdirs() }
        val uri = "$url/$affiliation.git"

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

    fun saveFilesAndClose(affiliation: String, files: Map<String, Map<String, Any?>>) {

        val git = checkoutRepoForAffiliation(affiliation)

        saveFilesAndClose(git, files)
    }

    fun saveFilesAndClose(git: Git, files: Map<String, Map<String, Any?>>) {
        try {
            writeAndAddChanges(git, files)

            val status = git.status().call()
            commitAllChanges(git, "added ${status.added.size} files, changed ${status.changed.size} files")
            push(git)
        } catch(ex: GitAPIException) {
            throw AuroraConfigException("Could not save because; '${ex.message}'")
        } finally {
            File(git.repository.directory.parent).deleteRecursively()
            git.close()
        }
    }

    fun getAllFilesForAffiliation(affiliation: String): Map<String, Map<String, Any?>> {

        val git = checkoutRepoForAffiliation(affiliation)

        return getAllFilesInRepo(git)
    }

    fun getAllFilesInRepo(git: Git): Map<String, Map<String, Any?>> {
        val folder = git.repository.directory.parentFile
        val allFilesInRepo = getAllFilesInFolder(folder)
        return allFilesInRepo.map {
            val conf: Map<*, *> = mapper.readValue(it, Map::class.java)
            val fileName = it.absoluteFile.absolutePath.replaceFirst(folder.absoluteFile.absolutePath, "")
            fileName to conf as Map<String, Any?>
        }.toMap()
    }

    private fun getAllFilesInFolder(folder: File): List<File> = folder.listFiles()
            .filter { !it.name.contains(".git") }
            .flatMap {
                if (it.isDirectory) getAllFilesInFolder(it)
                else listOf(it)
            }

    private fun writeAndAddChanges(git: Git, files: Map<String, Map<String, Any?>>) {

        files.forEach { (fileName, value) ->
            fileName.split("/")
                    .takeIf { it.size == 2 }
                    ?.let { File(git.repository.directory.parent, it[0]).mkdirs() }

            val file = File(git.repository.directory.parent, fileName).apply { createNewFile() }

            FileWriter(file, false).use { it.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value)) }

            git.add().addFilepattern(fileName).call()
        }
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

    private fun push(git: Git) {

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
    private fun markRelease(git: Git, namespace: String, name: String, resourceVersion: Int, commit: RevCommit) {

        createBranch(git, "$namespace-$name", commit)

        val tag = "$namespace-$name-$resourceVersion"
        git.tag().setAnnotated(true).setName(tag).setMessage(tag).call()
    }
}
