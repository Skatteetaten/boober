package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
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

@Service
class GitService(
        val mapper: ObjectMapper,
        val userDetails: UserDetailsProvider,
        @Value("\${boober.git.url}") val url: String,
        @Value("\${boober.git.dirPath}") val dirPath: String,
        @Value("\${boober.git.username}") val username: String,
        @Value("\${boober.git.password}") val password: String) {

    val cp = UsernamePasswordCredentialsProvider(username, password)

    // Token som url
    fun saveFiles(affiliation: String, branchName: String, files: Map<String, Map<String, Any?>>) {

        val git = initGit(affiliation, "token")

        writeAndAddChanges(git, files)
        val status = git.status().call()

        val revCommit = commitAllChanges(git, "$branchName: added ${status.added.size} files, changed ${status.changed.size} files")

        pushAsBoober(git)

        createBranch(git, branchName, revCommit)
        git.checkout().setName(branchName).call()

        pushAsBoober(git)

        git.close()
    }

    // TODO:Hvis konflikt må vi gjøre noe, skal ikke skje men du vet aldri
    fun initGit(affiliation: String, token: String): Git {

        val dir = File("$dirPath/$token/$affiliation")
        dir.mkdir()

        return if (!dir.resolve(".git").exists()) {
            Git.cloneRepository()
                    .setURI("$url/$affiliation.git")
                    .setCredentialsProvider(cp)
                    .setDirectory(dir)
                    .call()
        } else {
            val git = Git.open(dir)
            git.checkout().setName("master").call()
            git.pull().setCredentialsProvider(cp).call()
            git
        }
    }

    private fun writeAndAddChanges(git: Git, files: Map<String, Map<String, Any?>>) {

        files.forEach { (fileName, value) ->
            fileName.split("/")
                    .takeIf { it.size == 2 }
                    ?.let { File(git.repository.directory.parent, it[0]).mkdir() }

            val file = File(git.repository.directory.parent, fileName)
            file.createNewFile()

            val node: JsonNode = mapper.valueToTree(value)
            FileWriter(file, false).use { it.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node)) }

            git.add().addFilepattern(fileName).call()
        }
    }

    private fun commitAllChanges(git: Git, message: String): RevCommit {

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

    fun pushAsBoober(git: Git) {
        git.push()
                .setCredentialsProvider(cp)
                .call()
    }

    //dette er Det vi skal gjøre når det blir kjørt en setup kommando. resourceVersion får vi først etter at kommando er kjørt
//så dette må gjøres etter at vi har installert objektene. Det er resourceVersion i DC vi hovedsakelig bryr oss om.
//men hva med de tilfellene hvor vi ikke endrer dc men f.eks bare endrer en configMap? Da vil vi jo ikke ha resourceVersion på dc være endret.
//må vi faktisk ha med en annotated tag for hver ressurstype vi endrer?
/*

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
