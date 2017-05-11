package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File
import kotlin.system.measureTimeMillis

@Service
class AuroraConfigService(
        val gitService: GitService,
        val openShiftClient: OpenShiftClient,
        val mapper: ObjectMapper,
        val auroraDeploymentConfigService: AuroraDeploymentConfigService,
        val encryptionService: EncryptionService) {

    private val SECRET_FOLDER = ".secret"
    private val logger = LoggerFactory.getLogger(AuroraConfigService::class.java)

    fun save(affiliation: String, auroraConfig: AuroraConfig) {

        auroraDeploymentConfigService.validate(auroraConfig)

        gitService.saveFilesAndClose(affiliation, validateAndPrepareForSave(auroraConfig))
    }

    fun save(repo: Git, auroraConfig: AuroraConfig) {

        gitService.saveFilesAndClose(repo, validateAndPrepareForSave(auroraConfig))
    }

    private fun validateAndPrepareForSave(auroraConfig: AuroraConfig): Map<String, String> {
        validate(auroraConfig)
        val jsonFiles: Map<String, String> = auroraConfig.auroraConfigFiles.map {
            it.name to mapper.writerWithDefaultPrettyPrinter().writeValueAsString(it.contents)
        }.toMap()

        val encryptedSecrets = auroraConfig.secrets.map {
            val secretPath = it.key.split("/")
                    .takeIf { it.size >= 2 }
                    ?.let { it.subList(it.size - 2, it.size) }
                    ?.joinToString("/") ?: it.key

            val secretFolder = "$SECRET_FOLDER/$secretPath".replace("//", "/")
            secretFolder to encryptionService.encrypt(it.value)
        }.toMap()

        return jsonFiles + encryptedSecrets
    }

    fun findAuroraConfig(affiliation: String): AuroraConfig {

        return withAuroraConfig(affiliation, false, function = { it })
    }

    fun createAuroraConfigFromFiles(filesForAffiliation: Map<String, File>): AuroraConfig {

        val secretFiles: Map<String, String> = filesForAffiliation
                .filter { it.key.startsWith(SECRET_FOLDER) }
                .map { it.key.removePrefix("$SECRET_FOLDER/") to encryptionService.decrypt(it.value.readText()) }.toMap()

        val auroraConfigFiles = filesForAffiliation
                .filter { !it.key.startsWith(SECRET_FOLDER) }
                .map { AuroraConfigFile(it.key, mapper.readValue(it.value, Map::class.java) as JsonData) }

        return AuroraConfig(auroraConfigFiles = auroraConfigFiles, secrets = secretFiles)
    }

    fun patchAuroraConfigFileContents(name: String, auroraConfig: AuroraConfig, jsonPatchOp: String): JsonData {

        val patch: JsonPatch = mapper.readValue(jsonPatchOp, JsonPatch::class.java)

        val auroraConfigFile = auroraConfig.auroraConfigFiles.filter { it.name == name }.first()
        val originalContentsNode = mapper.convertValue(auroraConfigFile.contents, JsonNode::class.java)

        val patchedContentsNode = patch.apply(originalContentsNode)

        return mapper.treeToValue(patchedContentsNode, Map::class.java) as JsonData
    }

    fun withAuroraConfig(affiliation: String,
                         commitChanges: Boolean = true,
                         function: (AuroraConfig) -> AuroraConfig = { it -> it }): AuroraConfig {

        val startCheckout = System.currentTimeMillis()
        val repo = gitService.checkoutRepoForAffiliation(affiliation)
        logger.debug("Spent {} millis checking out gir repository", System.currentTimeMillis() - startCheckout)

        val filesForAffiliation: Map<String, File> = gitService.getAllFilesInRepo(repo)
        val auroraConfig = createAuroraConfigFromFiles(filesForAffiliation)

        val newAuroraConfig = function(auroraConfig)

        if (commitChanges) {
            measureTimeMillis {
                save(repo, newAuroraConfig)
            }.let { logger.debug("Spent {} millis committing and pushing to git", it) }
        } else {
            measureTimeMillis {
                gitService.closeRepository(repo)
            }.let { logger.debug("Spent {} millis closing git repository", it) }
        }

        return newAuroraConfig
    }
}
