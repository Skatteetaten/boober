package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.fge.jsonpatch.JsonPatch
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.service.GitServices.Domain.AURORA_CONFIG
import no.skatteetaten.aurora.boober.service.GitServices.TargetDomain
import org.eclipse.jgit.api.errors.InvalidRemoteException
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

        updateLocalFilesFromGit(name)
        val file = getAuroraConfigFile(name, fileName)
        return if (file.exists()) AuroraConfigFile(file.path, jacksonObjectMapper().readValue(file))
        else throw IllegalArgumentException("No such file $fileName in AuroraConfig $name")
    }

    fun updateAuroraConfigFile(name: String, fileName: String, contents: String, fileHash: String? = null): AuroraConfig {

        updateLocalFilesFromGit(name)
        jacksonObjectMapper().readValue(contents, JsonNode::class.java)
        getAuroraConfigFile(name, fileName).writeText(contents)

        // TODO: Commit
        return findAuroraConfig(name)
    }

    fun patchAuroraConfigFile(name: String, filename: String, jsonPatchOp: String, configFileVersion: String, validateVersions: Boolean): AuroraConfig {

        val auroraConfig = findAuroraConfig(name)
        val mapper = jacksonObjectMapper()
        val patch: JsonPatch = mapper.readValue(jsonPatchOp, JsonPatch::class.java)

        val auroraConfigFile = auroraConfig.auroraConfigFiles.filter { it.name == filename }.first()
        val originalContentsNode = mapper.convertValue(auroraConfigFile.contents, JsonNode::class.java)

        val fileContents = patch.apply(originalContentsNode)
        auroraConfig.updateFile(filename, fileContents, configFileVersion)
        // TODO: Save and commit
        return auroraConfig
    }

    private fun getAuroraConfigFile(name: String, fileName: String) =
            File(getAuroraConfigFolder(name), fileName)

    private fun getAuroraConfigFolder(name: String) = File(gitService.checkoutPath, name)

    private fun updateLocalFilesFromGit(name: String) {
        try {
            gitService.checkoutRepository(name).close()
        } catch (e: InvalidRemoteException) {
            throw IllegalArgumentException("No such AuroraConfig $name")
        } catch (e: Exception) {
            throw AuroraConfigServiceException("An unexpected error occurred when checking out AuroraConfig with name $name", e)
        }
    }
}