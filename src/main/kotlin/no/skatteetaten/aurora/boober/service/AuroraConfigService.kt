package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.fge.jsonpatch.JsonPatch
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File
import kotlin.system.measureTimeMillis

@Service
class AuroraConfigService(
        val gitService: GitService,
        val auroraDeploymentConfigService: AuroraDeploymentConfigService,
        val mapper: ObjectMapper,
        val encryptionService: EncryptionService) {

    private val GIT_SECRET_FOLDER = ".secret"
    private val logger = LoggerFactory.getLogger(AuroraConfigService::class.java)

    fun findAuroraConfig(affiliation: String): AuroraConfig {

        return withAuroraConfig(affiliation, false)
    }

    fun saveAuroraConfig(affiliation: String, auroraConfig: AuroraConfig): AuroraConfig {

        return withAuroraConfig(affiliation, function = { auroraConfig })
    }

    fun patchAuroraConfigFile(affiliation: String, filename: String, jsonPatchOp: String): AuroraConfig {

        return withAuroraConfig(affiliation, true, { auroraConfig: AuroraConfig ->
            val patch: JsonPatch = mapper.readValue(jsonPatchOp, JsonPatch::class.java)

            val auroraConfigFile = auroraConfig.auroraConfigFiles.filter { it.name == filename }.first()
            val originalContentsNode = mapper.convertValue(auroraConfigFile.contents, JsonNode::class.java)

            val fileContents = patch.apply(originalContentsNode)
            auroraConfig.updateFile(filename, fileContents)
        })
    }

    fun updateAuroraConfigFile(affiliation: String, filename: String, fileContents: JsonNode): AuroraConfig {
        return withAuroraConfig(affiliation, true, { auroraConfig: AuroraConfig ->
            auroraConfig.updateFile(filename, fileContents)
        })
    }

    private fun withAuroraConfig(affiliation: String,
                                 commitChanges: Boolean = true,
                                 function: (AuroraConfig) -> AuroraConfig = { it -> it }): AuroraConfig {

        val startCheckout = System.currentTimeMillis()
        val repo = gitService.checkoutRepoForAffiliation(affiliation)
        logger.debug("Spent {} millis checking out gir repository", System.currentTimeMillis() - startCheckout)

        val filesForAffiliation: Map<String, File> = gitService.getAllFilesInRepo(repo)
        val auroraConfig = createAuroraConfigFromFiles(filesForAffiliation)

        val newAuroraConfig = function(auroraConfig)
        auroraDeploymentConfigService.validate(newAuroraConfig)

        if (commitChanges) {
            measureTimeMillis {
                gitService.saveFilesAndClose(repo, prepareFilesForSave(newAuroraConfig))
            }.let { logger.debug("Spent {} millis committing and pushing to git", it) }
        } else {
            measureTimeMillis {
                gitService.closeRepository(repo)
            }.let { logger.debug("Spent {} millis closing git repository", it) }
        }

        return newAuroraConfig
    }

    private fun createAuroraConfigFromFiles(filesForAffiliation: Map<String, File>): AuroraConfig {

        val secretFiles: Map<String, String> = filesForAffiliation
                .filter { it.key.startsWith(GIT_SECRET_FOLDER) }
                .map { it.key to encryptionService.decrypt(it.value.readText()) }.toMap()

        val auroraConfigFiles = filesForAffiliation
                .filter { !it.key.startsWith(GIT_SECRET_FOLDER) }
                .map { AuroraConfigFile(it.key, mapper.readValue(it.value)) }

        return AuroraConfig(auroraConfigFiles = auroraConfigFiles, secrets = secretFiles)
    }

    private fun prepareFilesForSave(auroraConfig: AuroraConfig): Map<String, String> {

        val secrets = auroraConfig.convertSecretFilesToString(GIT_SECRET_FOLDER)
        val encryptedSecrets = secrets.map { it.key to encryptionService.encrypt(it.value) }.toMap()

        return auroraConfig.convertFilesToString(mapper) + encryptedSecrets
    }

}
