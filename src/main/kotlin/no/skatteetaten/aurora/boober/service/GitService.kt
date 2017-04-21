package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.ObjectMapper
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.utils.use
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File
import java.io.FileWriter
import java.util.*

fun Git.deleteCloneAndClose() {
    File(this.repository.directory.parent).deleteRecursively()
    this.close()
}

@Service
class GitService(
        val setupService: SetupService,
        val mapper: ObjectMapper,
        val userDetails: UserDetailsProvider,
        @Value("\${boober.git.url}") val url: String,
        @Value("\${boober.git.dirPath}") val dirPath: String,
        @Value("\${boober.git.username}") val username: String,
        @Value("\${boober.git.password}") val password: String) {

    val cp = UsernamePasswordCredentialsProvider(username, password)

    // Delete folder on failure?
    fun saveFiles(affiliation: String, auroraConfig: AuroraConfig) {

        val git = initGit(affiliation)

        writeAndAddChanges(git, auroraConfig.aocConfigFiles)
        val status = git.status().call()

        val files = getAllFilesInRepo(git.repository.directory.parentFile)
                .map {
                    val name = it.toRelativeString(git.repository.directory.parentFile)
                    val conf = mapper.readValue(it, Map::class.java)
                    name to conf as Map<String, Any?>
                }.toMap()

        val configToValidation = AuroraConfig(files)
        val appids = configToValidation.getApplicationIds()

        val result = setupService.createAuroraDcsForApplications(configToValidation, appids)

        commitAllChanges(git, "added ${status.added.size} files, changed ${status.changed.size} files")

        git.deleteCloneAndClose()
    }

    private fun getAllFilesInRepo(folder: File): List<File> = folder.listFiles()
            .filter { !it.name.contains(".git") }
            .flatMap {
                if (it.isDirectory) getAllFilesInRepo(it)
                else listOf(it)
            }

    fun getFiles(git: Git, aid: ApplicationId): Map<String, Map<String, Any?>> {

        val requiredFilesForApplication = setOf(
                "about.json",
                "${aid.applicationName}.json",
                "${aid.environmentName}/about.json",
                "${aid.environmentName}/${aid.applicationName}.json")

        return requiredFilesForApplication.map {
            val file = File(git.repository.directory.parent).resolve(it)
            val conf = mapper.readValue(file, Map::class.java)
            it to conf as Map<String, Any?>
        }.toMap()
    }

    fun initGit(affiliation: String): Git {

        val dir = File("$dirPath/${UUID.randomUUID()}").apply { mkdirs() }
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

    fun writeAndAddChanges(git: Git, files: Map<String, Map<String, Any?>>) {

        files.forEach { (fileName, value) ->
            fileName.split("/")
                    .takeIf { it.size == 2 }
                    ?.let { File(git.repository.directory.parent, it[0]).mkdirs() }

            val file = File(git.repository.directory.parent, fileName).apply { createNewFile() }

            FileWriter(file, false).use { it.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value)) }

            git.add().addFilepattern(fileName).call()
        }
    }

    fun commitAllChanges(git: Git, message: String): RevCommit {

        return git.commit()
                .setAll(true)
                .setAllowEmpty(false)
                .setAuthor(userDetails.getPersonIdent())
                .setMessage(message)
                .call()
    }

    fun createBranch(git: Git, branchName: String, commit: RevCommit): Ref {

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
    fun markRelease(git: Git, namespace: String, name: String, resourceVersion: Int, commit: RevCommit) {

        createBranch(git, "$namespace-$name", commit)

        val tag = "$namespace-$name-$resourceVersion"
        git.tag().setAnnotated(true).setName(tag).setMessage(tag).call()
    }
}
