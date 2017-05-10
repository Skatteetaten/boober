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

        val jsonFiles: Map<String, String> = auroraConfig.auroraConfigFiles.map {
            it.name to mapper.writerWithDefaultPrettyPrinter().writeValueAsString(it.contents)
        }.toMap()

        val encryptedSecrets = auroraConfig.secrets.map {
            "$SECRET_FOLDER/${it.key}" to encryptionService.encrypt(it.value)
        }.toMap()

        gitService.saveFilesAndClose(affiliation, jsonFiles + encryptedSecrets)
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
                .map { AuroraConfigFile(it.key, mapper.readValue(it.value)) }

        return AuroraConfig(auroraConfigFiles = auroraConfigFiles, secrets = secretFiles)
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
                save(affiliation, newAuroraConfig)
            }.let { logger.debug("Spent {} millis committing and pushing to git", it) }
        } else {
            measureTimeMillis {
                gitService.closeRepository(repo)
            }.let { logger.debug("Spent {} millis closing git repository", it) }
        }

        return newAuroraConfig
    }
}
