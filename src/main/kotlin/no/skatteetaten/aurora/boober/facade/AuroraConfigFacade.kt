package no.skatteetaten.aurora.boober.facade

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.fge.jsonpatch.JsonPatch
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import no.skatteetaten.aurora.boober.service.EncryptionService
import no.skatteetaten.aurora.boober.service.GitService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File
import kotlin.system.measureTimeMillis

@Service
class AuroraConfigFacade(
        val gitService: GitService,
        val mapper: ObjectMapper,
        val encryptionService: EncryptionService,
        val auroraConfigValidationService: AuroraConfigService) {

    private val GIT_SECRET_FOLDER = ".secret"
    private val logger = LoggerFactory.getLogger(AuroraConfigFacade::class.java)

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

        if (commitChanges) {
            auroraConfigValidationService.validate(newAuroraConfig)
            measureTimeMillis {
                val encryptedSecretsFiles = encryptSecrets(auroraConfig, newAuroraConfig, filesForAffiliation)
                val configFiles = convertFilesToString(newAuroraConfig.auroraConfigFiles)
                gitService.saveFilesAndClose(repo, configFiles + encryptedSecretsFiles)
            }.let { logger.debug("Spent {} millis committing and pushing to git", it) }
        } else {
            measureTimeMillis {
                gitService.closeRepository(repo)
            }.let { logger.debug("Spent {} millis closing git repository", it) }
        }

        return newAuroraConfig
    }

    private fun encryptSecrets(oldAuroraConfig: AuroraConfig, newAuroraConfig: AuroraConfig, filesFromGit: Map<String, File>): Map<String, String> {

        val oldSecrets = convertSecretFilesToString(oldAuroraConfig)
        val newSecrets = convertSecretFilesToString(newAuroraConfig)

        val encryptedChangedSecrets = newSecrets
                .filter { oldSecrets.containsKey(it.key) }
                .filter { it.value != oldSecrets[it.key] }
                .map { it.key to encryptionService.encrypt(it.value) }.toMap()

        val encryptedNewSecrets = newSecrets
                .filter { !oldSecrets.containsKey(it.key) }
                .map { it.key to encryptionService.encrypt(it.value) }.toMap()

        val encryptedSecrets = encryptedNewSecrets + encryptedChangedSecrets

        val encryptedOldSecrets = filesFromGit
                .filter { it.key.startsWith(GIT_SECRET_FOLDER) }
                .filter { !encryptedSecrets.containsKey(it.key) }
                .map { it.key to it.value.readText() }.toMap()

        return encryptedSecrets + encryptedOldSecrets
    }

    private fun convertFilesToString(auroraConfigFiles: List<AuroraConfigFile>): Map<String, String> {

        return auroraConfigFiles.map {
            it.name to mapper.writerWithDefaultPrettyPrinter().writeValueAsString(it.contents)
        }.toMap()
    }

    private fun convertSecretFilesToString(auroraConfig: AuroraConfig): Map<String, String> {

        return auroraConfig.secrets.map {
            val applicationSecretPath = it.key.split("/")
                    .takeIf { it.size >= 2 }
                    ?.let { it.subList(it.size - 2, it.size) }
                    ?.joinToString("/") ?: it.key

            val secretFolder = applicationSecretPath.split("/")[0]
            val gitSecretFolder = "$GIT_SECRET_FOLDER/$applicationSecretPath".replace("//", "/")

            auroraConfig.auroraConfigFiles
                    .filter { it.contents.has("secretFolder") }
                    .filter { it.contents.get("secretFolder").asText().contains(secretFolder) }
                    .forEach {
                        val folder = applicationSecretPath.split("/")[0]
                        (it.contents as ObjectNode).put("secretFolder", "$GIT_SECRET_FOLDER/$folder")
                    }

            gitSecretFolder to it.value
        }.toMap()
    }

    private fun createAuroraConfigFromFiles(filesForAffiliation: Map<String, File>, decryptSecrets: Boolean = true): AuroraConfig {

        val secretFiles: Map<String, String> = filesForAffiliation
                .filter { it.key.startsWith(GIT_SECRET_FOLDER) }
                .map { (key, value) ->
                    if (decryptSecrets) key to encryptionService.decrypt(value.readText())
                    else key to value.readText()
                }.toMap()

        val auroraConfigFiles = filesForAffiliation
                .filter { !it.key.startsWith(GIT_SECRET_FOLDER) }
                .map { AuroraConfigFile(it.key, mapper.readValue(it.value)) }

        return AuroraConfig(auroraConfigFiles = auroraConfigFiles, secrets = secretFiles)
    }
}
