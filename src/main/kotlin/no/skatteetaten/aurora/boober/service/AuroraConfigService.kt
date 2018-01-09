package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.fge.jsonpatch.JsonPatch
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.service.GitServices.Domain.AURORA_CONFIG
import no.skatteetaten.aurora.boober.service.GitServices.TargetDomain
import org.apache.commons.io.FileUtils
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.InvalidRemoteException
import org.eclipse.jgit.lib.PersonIdent
import org.springframework.stereotype.Service
import java.io.File

@Service
class AuroraConfigService(@TargetDomain(AURORA_CONFIG) val gitService: GitService) {

    fun findAuroraConfig(name: String): AuroraConfig {

        updateLocalFilesFromGit(name)
        return AuroraConfig.fromFolder("${gitService.checkoutPath}/$name")
    }

    fun findAuroraConfigFileNames(name: String): List<String> {

        val auroraConfig = findAuroraConfig(name)
        return auroraConfig.auroraConfigFiles.map { it.name }
    }

    fun findAuroraConfigFile(name: String, fileName: String): AuroraConfigFile? {

        // TODO: Implement using AuroraConfig.auroraConfigFiles
        updateLocalFilesFromGit(name)
        val file = getAuroraConfigFile(name, fileName)
        return if (file.exists()) AuroraConfigFile(file.path, jacksonObjectMapper().readValue(file))
        else throw IllegalArgumentException("No such file $fileName in AuroraConfig $name")
    }

    fun save(auroraConfig: AuroraConfig): AuroraConfig {

        val mapper = jacksonObjectMapper()

        val repo = getUpdatedRepo(auroraConfig.affiliation)
        val checkoutDir = getAuroraConfigFolder(auroraConfig.affiliation)
        val existing = AuroraConfig.fromFolder(checkoutDir)
        existing.auroraConfigFiles.forEach {
            val outputFile = File(checkoutDir, it.name)
            FileUtils.deleteQuietly(outputFile)
        }

        auroraConfig.auroraConfigFiles.forEach {
            val prettyContent = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(it.contents)
            val outputFile = File(getAuroraConfigFolder(auroraConfig.affiliation), it.name)
            FileUtils.forceMkdirParent(outputFile)
            outputFile.writeText(prettyContent)
        }

        gitService.commitAndPushChanges(repo)
        repo.close()

        return auroraConfig
    }

    fun updateAuroraConfigFile(name: String, fileName: String, contents: String, previousVersion: String? = null): AuroraConfig {

        val jsonContents = jacksonObjectMapper().readValue(contents, JsonNode::class.java)
        val auroraConfig = findAuroraConfig(name).updateFile(fileName, jsonContents, previousVersion)

        return save(auroraConfig)
    }

    fun patchAuroraConfigFile(name: String, filename: String, jsonPatchOp: String, previousVersion: String? = null): AuroraConfig {

        val auroraConfig = findAuroraConfig(name)
        val mapper = jacksonObjectMapper()
        val patch: JsonPatch = mapper.readValue(jsonPatchOp, JsonPatch::class.java)

        val auroraConfigFile = auroraConfig.findFile(filename)
                ?: throw IllegalArgumentException("No such file $filename in AuroraConfig ${auroraConfig.affiliation}")
        val originalContentsNode = mapper.convertValue(auroraConfigFile.contents, JsonNode::class.java)

        val fileContents = patch.apply(originalContentsNode)
        val updatedAuroraConfig = auroraConfig.updateFile(filename, fileContents, previousVersion)

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