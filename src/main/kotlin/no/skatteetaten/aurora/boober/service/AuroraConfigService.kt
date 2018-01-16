package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.service.GitServices.Domain.AURORA_CONFIG
import no.skatteetaten.aurora.boober.service.GitServices.TargetDomain
import org.apache.commons.io.FileUtils
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.InvalidRemoteException
import org.slf4j.Logger
import org.slf4j.LoggerFactory.getLogger
import org.springframework.stereotype.Service
import org.springframework.util.StopWatch
import java.io.File

@Service
class AuroraConfigService(@TargetDomain(AURORA_CONFIG) val gitService: GitService, val bitbucketProjectService: BitbucketProjectService) {


    val logger: Logger = getLogger(AuroraConfigService::class.java)

    fun findAllAuroraConfigNames(): List<String> {

        // TODO: Finding the name of all repositories for a given project in bitbucket should probably be hidden behind the GitService service.
        return bitbucketProjectService.getAllSlugs()
    }

    fun findAuroraConfig(name: String): AuroraConfig {

        updateLocalFilesFromGit(name)
        return AuroraConfig.fromFolder("${gitService.checkoutPath}/$name")
    }

    fun findAuroraConfigFileNames(name: String): List<String> {

        val auroraConfig = findAuroraConfig(name)
        return auroraConfig.auroraConfigFiles.map { it.name }
    }

    fun findAuroraConfigFile(name: String, fileName: String): AuroraConfigFile? {

        val auroraConfig = findAuroraConfig(name)
        return auroraConfig.findFile(fileName)
                ?: throw IllegalArgumentException("No such file $fileName in AuroraConfig $name")
    }

    fun save(auroraConfig: AuroraConfig): AuroraConfig {
        val watch = StopWatch()
        watch.start("validate")
        auroraConfig.validate()
        watch.stop()

        watch.start("createAuroraConfig")
        val mapper = jacksonObjectMapper()
        val checkoutDir = getAuroraConfigFolder(auroraConfig.affiliation)
        val repo = Git.open(checkoutDir)
        val existing = AuroraConfig.fromFolder(checkoutDir)
        watch.stop()

        watch.start("delete")
        existing.auroraConfigFiles.forEach {
            val outputFile = File(checkoutDir, it.name)
            FileUtils.deleteQuietly(outputFile)
        }
        watch.stop()

        watch.start("add")
        auroraConfig.auroraConfigFiles.forEach {
            val prettyContent = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(it.contents)
            val outputFile = File(getAuroraConfigFolder(auroraConfig.affiliation), it.name)
            FileUtils.forceMkdirParent(outputFile)
            outputFile.writeText(prettyContent)
        }
        watch.stop()

        watch.start("git")
        gitService.commitAndPushChanges(repo)
        repo.close()
        watch.stop()

        logger.debug(watch.prettyPrint())
        return auroraConfig
    }

    fun updateAuroraConfigFile(name: String, fileName: String, contents: String, previousVersion: String? = null): AuroraConfig {

        val jsonContents = jacksonObjectMapper().readValue(contents, JsonNode::class.java)
        val oldAuroraConfig = findAuroraConfig(name)
        val auroraConfig = oldAuroraConfig.updateFile(fileName, jsonContents, previousVersion)

        return save(auroraConfig)
    }

    fun patchAuroraConfigFile(name: String, filename: String, jsonPatchOp: String, previousVersion: String? = null): AuroraConfig {

        val auroraConfig = findAuroraConfig(name)
        val updatedAuroraConfig = auroraConfig.patchFile(filename, jsonPatchOp, previousVersion)

        val aid = updatedAuroraConfig.getApplicationIds().filter{
            val files = updatedAuroraConfig.getFilesForApplication(it)
            files.any{ it.name == filename}
        }

        //du har filen du har endet
        //modifisere gammel auroraConfig med fila.
        //hente alle AID. Og alle filer de har.
        //filtrer på at de inneholder filen vi har endret.

        return save(updatedAuroraConfig)
    }


    private fun getAuroraConfigFile(name: String, fileName: String) =
            File(getAuroraConfigFolder(name), fileName)

    private fun getAuroraConfigFolder(name: String) = File(gitService.checkoutPath, name)

    private fun updateLocalFilesFromGit(name: String) {
        val repository = getUpdatedRepo(name)
        repository.close()
    }

    private fun getUpdatedRepo(name: String): Git {
        return try {
            gitService.checkoutRepository(name)
        } catch (e: InvalidRemoteException) {
            throw IllegalArgumentException("No such AuroraConfig $name")
        } catch (e: Exception) {
            throw AuroraConfigServiceException("An unexpected error occurred when checking out AuroraConfig with name $name", e)
        }
    }
}