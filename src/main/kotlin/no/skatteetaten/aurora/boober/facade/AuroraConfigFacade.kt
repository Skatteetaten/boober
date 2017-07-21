package no.skatteetaten.aurora.boober.facade

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.fge.jsonpatch.JsonPatch
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import no.skatteetaten.aurora.boober.service.GitService
import no.skatteetaten.aurora.boober.service.SecretVaultService
import no.skatteetaten.aurora.boober.service.internal.AuroraVersioningException
import no.skatteetaten.aurora.boober.service.internal.VersioningError
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.RevCommit
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File

@Service
class AuroraConfigFacade(
        val gitService: GitService,
        val mapper: ObjectMapper,
        val auroraConfigService: AuroraConfigService,
        val secretVaultService: SecretVaultService) {

    private val GIT_SECRET_FOLDER = ".secret"
    private val logger = LoggerFactory.getLogger(AuroraConfigFacade::class.java)


    fun findAuroraConfig(affiliation: String): AuroraConfig {
        val repo = getRepo(affiliation)
        val allFilesInRepo: Map<String, Pair<RevCommit?, File>> = gitService.getAllFilesInRepo(repo)
        return createAuroraConfigFromFiles(allFilesInRepo, affiliation)
    }

    fun createAuroraConfig(repo: Git, affiliation: String, overrides: List<AuroraConfigFile>): AuroraConfig {
        val allFilesInRepo: Map<String, Pair<RevCommit?, File>> = gitService.getAllFilesInRepo(repo)
        val config = createAuroraConfigFromFiles(allFilesInRepo, affiliation)
        return config.copy(auroraConfigFiles = config.auroraConfigFiles + overrides)
    }

    fun saveAuroraConfig(affiliation: String, auroraConfig: AuroraConfig, validateVersions: Boolean): AuroraConfig {
        return withAuroraConfig(affiliation, validateVersions, {
            auroraConfig
        })
    }

    fun patchAuroraConfigFile(affiliation: String, filename: String, jsonPatchOp: String, configFileVersion: String, validateVersions: Boolean): AuroraConfig {

        return withAuroraConfig(affiliation, validateVersions, { auroraConfig: AuroraConfig ->
            val patch: JsonPatch = mapper.readValue(jsonPatchOp, JsonPatch::class.java)

            val auroraConfigFile = auroraConfig.auroraConfigFiles.filter { it.name == filename }.first()
            val originalContentsNode = mapper.convertValue(auroraConfigFile.contents, JsonNode::class.java)

            val fileContents = patch.apply(originalContentsNode)
            auroraConfig.updateFile(filename, fileContents, configFileVersion)
        })
    }

    fun updateAuroraConfigFile(affiliation: String, filename: String, fileContents: JsonNode, configFileVersion: String, validateVersions: Boolean): AuroraConfig {
        return withAuroraConfig(affiliation, validateVersions, { auroraConfig: AuroraConfig ->
            auroraConfig.updateFile(filename, fileContents, configFileVersion)
        })
    }


    private fun withAuroraConfig(affiliation: String,
                                 validateVersions: Boolean,
                                 function: (AuroraConfig) -> AuroraConfig = { it -> it }): AuroraConfig {

        val repo = getRepo(affiliation)

        val allFilesInRepo: Map<String, Pair<RevCommit?, File>> = gitService.getAllFilesInRepo(repo)

        val auroraConfig = createAuroraConfigFromFiles(allFilesInRepo, affiliation)

        val vaults = secretVaultService.getVaults(repo)
        val newAuroraConfig = function(auroraConfig)

        if (validateVersions) {
            validateGitVersion(auroraConfig, newAuroraConfig, allFilesInRepo)
        }
        auroraConfigService.validate(newAuroraConfig, vaults)
        commitAuroraConfig(repo, newAuroraConfig)

        return newAuroraConfig
    }

    private fun validateGitVersion(auroraConfig: AuroraConfig, newAuroraConfig: AuroraConfig, allFilesInRepo: Map<String, Pair<RevCommit?, File>>) {
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

    private fun createAuroraConfigFromFiles(filesFromGit: Map<String, Pair<RevCommit?, File>>, affiliation: String): AuroraConfig {

        val auroraConfigFiles = filesFromGit
                .filter { !it.key.startsWith(GIT_SECRET_FOLDER) }
                .map { AuroraConfigFile(it.key, mapper.readValue(it.value.second), version = it.value.first?.abbreviate(7)?.name()) }

        return AuroraConfig(auroraConfigFiles = auroraConfigFiles, affiliation = affiliation)
    }


    private fun commitAuroraConfig(repo: Git,
                                   newAuroraConfig: AuroraConfig) {

        val configFiles: Map<String, String> = newAuroraConfig.auroraConfigFiles.map {
            it.name to mapper.writerWithDefaultPrettyPrinter().writeValueAsString(it.contents)
        }.toMap()


        //when we save auroraConfig we do not want to mess with secret vault files
        val keep: (String) -> Boolean = { it -> !it.startsWith(GIT_SECRET_FOLDER) }
        gitService.saveFilesAndClose(repo, configFiles, keep)
    }

}
