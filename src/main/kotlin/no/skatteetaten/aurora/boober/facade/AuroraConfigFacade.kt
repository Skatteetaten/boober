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
import no.skatteetaten.aurora.boober.service.internal.AuroraVersioningException
import no.skatteetaten.aurora.boober.service.internal.VersioningError
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.RevCommit
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File
import kotlin.system.measureTimeMillis

@Service
class AuroraConfigFacade(
        val gitService: GitService,
        val mapper: ObjectMapper,
        val encryptionService: EncryptionService,
        val auroraConfigService: AuroraConfigService) {

    private val GIT_SECRET_FOLDER = ".secret"
    private val logger = LoggerFactory.getLogger(AuroraConfigFacade::class.java)

    fun findAuroraConfig(affiliation: String): AuroraConfig {

        return withAuroraConfig(affiliation, false)
    }

    fun saveAuroraConfig(affiliation: String, auroraConfig: AuroraConfig): AuroraConfig {

        return withAuroraConfig(affiliation, validateVersions = false, function = {
            val originalSecrets = it.secrets
            val updatedAuroraConfig = updateAuroraConfigSecretPaths(auroraConfig)

            updatedAuroraConfig.copy(secrets = originalSecrets + updatedAuroraConfig.secrets)
        })
    }

    fun patchAuroraConfigFile(affiliation: String, filename: String, jsonPatchOp: String, configFileVersion: String): AuroraConfig {

        return withAuroraConfig(affiliation, function={ auroraConfig: AuroraConfig ->
            val patch: JsonPatch = mapper.readValue(jsonPatchOp, JsonPatch::class.java)

            val auroraConfigFile = auroraConfig.auroraConfigFiles.filter { it.name == filename }.first()
            val originalContentsNode = mapper.convertValue(auroraConfigFile.contents, JsonNode::class.java)

            val fileContents = patch.apply(originalContentsNode)
            auroraConfig.updateFile(filename, fileContents, configFileVersion)
        })
    }

    fun updateAuroraConfigFile(affiliation: String, filename: String, fileContents: JsonNode, configFileVersion: String): AuroraConfig {
        return withAuroraConfig(affiliation, function={ auroraConfig: AuroraConfig ->

            auroraConfig.updateFile(filename, fileContents, configFileVersion)
        })
    }

    fun deleteSecrets(affiliation: String, secrets: List<String>): AuroraConfig {

        val repo = getRepo(affiliation)
        val filesForAffiliation: MutableMap<String, Pair<RevCommit?, File>> = gitService.getAllFilesInRepo(repo).toMutableMap()

        secrets.filter { it.startsWith(GIT_SECRET_FOLDER) }
                .forEach { filesForAffiliation.remove(it) }

        val auroraConfig = createAuroraConfigFromFiles(filesForAffiliation)

        val files = filesForAffiliation.mapValues { it.value.second }
        commitAuroraConfig(repo, auroraConfig, auroraConfig, files)

        return auroraConfig
    }

    private fun withAuroraConfig(affiliation: String,
                                 commitChanges: Boolean = true,
                                 validateVersions: Boolean = true,
                                 function: (AuroraConfig) -> AuroraConfig = { it -> it }): AuroraConfig {

        val repo = getRepo(affiliation)

        val allFilesInRepo: Map<String, Pair<RevCommit?, File>> = gitService.getAllFilesInRepo(repo)

        val auroraConfig = createAuroraConfigFromFiles(allFilesInRepo)

        val newAuroraConfig = function(auroraConfig)

        if (commitChanges) {

            if(validateVersions) {
                validateGitVersion(auroraConfig, newAuroraConfig, allFilesInRepo)
            }
            measureTimeMillis {
                auroraConfigService.validate(newAuroraConfig)
                commitAuroraConfig(repo, auroraConfig, newAuroraConfig, allFilesInRepo.mapValues { it.value.second })
            }.let { logger.debug("Spent {} millis committing and pushing to git", it) }
        } else {
            measureTimeMillis {
                gitService.closeRepository(repo)
            }.let { logger.debug("Spent {} millis closing git repository", it) }
        }

        return newAuroraConfig
    }

    private fun validateGitVersion(auroraConfig: AuroraConfig, newAuroraConfig: AuroraConfig, allFilesInRepo: Map<String, Pair<RevCommit?, File>>) {
        //TODO:Validate secrets aswell
        val oldVersions = auroraConfig.getVersions().filterValues { it != null }
        val invalidVersions = newAuroraConfig.getVersions().filter {
            oldVersions[it.key] != it.value
        }

        if (invalidVersions.isNotEmpty()) {
            val gitInfo: Map<String, RevCommit> = allFilesInRepo
                    .filter { it.value.first != null }
                    .mapValues { it.value.first!! }

            val errors = invalidVersions.map {
                val commit = gitInfo[it.key]!!
                VersioningError(it.key, commit.authorIdent.name, commit.authorIdent.`when`)
            }

            throw AuroraVersioningException("Source file has changed since you fetched it", errors)
        }
    }

    private fun getRepo(affiliation: String): Git {

        val startCheckout = System.currentTimeMillis()
        val repo = gitService.checkoutRepoForAffiliation(affiliation)
        logger.debug("Spent {} millis checking out gir repository", System.currentTimeMillis() - startCheckout)

        return repo
    }

    private fun createAuroraConfigFromFiles(filesFromGit: Map<String, Pair<RevCommit?, File>>, decryptSecrets: Boolean = true): AuroraConfig {

        val secretFiles: Map<String, String> = filesFromGit
                .filter { it.key.startsWith(GIT_SECRET_FOLDER) }
                .map { (key, value) ->
                    if (decryptSecrets) key to encryptionService.decrypt(value.second.readText())
                    else key to value.second.readText()
                }.toMap()

        val auroraConfigFiles = filesFromGit
                .filter { !it.key.startsWith(GIT_SECRET_FOLDER) }
                .map { AuroraConfigFile(it.key, mapper.readValue(it.value.second), version = it.value.first?.abbreviate(7)?.name()) }

        return AuroraConfig(auroraConfigFiles = auroraConfigFiles, secrets = secretFiles)
    }

    private fun updateAuroraConfigSecretPaths(auroraConfig: AuroraConfig): AuroraConfig {

        return auroraConfig.secrets.entries.fold(auroraConfig) { acc, path ->

            val gitSecretFolder = createGitSecretFolderPath(path)
            val secretFolder = gitSecretFolder.removePrefix("$GIT_SECRET_FOLDER/").split("/")[0]
            val files = acc.auroraConfigFiles.toMutableList()

            files.filter { it.contents.has("secretFolder") }
                    .filter { it.contents.get("secretFolder").asText().contains(secretFolder) }
                    .forEach {
                        (it.contents as ObjectNode).put("secretFolder", "$GIT_SECRET_FOLDER/$secretFolder")
                    }

            val secrets = acc.secrets.map { createGitSecretFolderPath(it) to it.value }.toMap()

            acc.copy(files, secrets)
        }
    }

    private fun commitAuroraConfig(repo: Git,
                                   originalAuroraConfig: AuroraConfig,
                                   newAuroraConfig: AuroraConfig,
                                   allFilesInRepo: Map<String, File>) {

        val encryptedSecretsFiles: Map<String, String> = encryptSecrets(originalAuroraConfig, newAuroraConfig, allFilesInRepo)

        val configFiles: Map<String, String> = newAuroraConfig.auroraConfigFiles.map {
            it.name to mapper.writerWithDefaultPrettyPrinter().writeValueAsString(it.contents)
        }.toMap()

        gitService.saveFilesAndClose(repo, configFiles + encryptedSecretsFiles)
    }

    private fun encryptSecrets(oldAuroraConfig: AuroraConfig,
                               newAuroraConfig: AuroraConfig,
                               allFilesInRepo: Map<String, File>): Map<String, String> {

        val oldSecrets = oldAuroraConfig.secrets
        val newSecrets = newAuroraConfig.secrets

        val encryptedChangedSecrets = newSecrets
                .filter { oldSecrets.containsKey(it.key) }
                .filter { it.value != oldSecrets[it.key] }
                .map { it.key to encryptionService.encrypt(it.value) }.toMap()

        val encryptedNewSecrets = newSecrets
                .filter { !oldSecrets.containsKey(it.key) }
                .map { it.key to encryptionService.encrypt(it.value) }.toMap()

        val encryptedSecrets = encryptedNewSecrets + encryptedChangedSecrets

        val encryptedOldSecrets = allFilesInRepo
                .filter { it.key.startsWith(GIT_SECRET_FOLDER) }
                .filter { !encryptedSecrets.containsKey(it.key) }
                .map { it.key to it.value.readText() }.toMap()

        return encryptedSecrets + encryptedOldSecrets
    }

    private fun createGitSecretFolderPath(secretPath: Map.Entry<String, String>): String {

        val applicationSecretPath = secretPath.key.split("/")
                .takeIf { it.size >= 2 }
                ?.let { it.subList(it.size - 2, it.size) }
                ?.joinToString("/") ?: secretPath.key

        return "$GIT_SECRET_FOLDER/$applicationSecretPath".replace("//", "/")
    }
}
