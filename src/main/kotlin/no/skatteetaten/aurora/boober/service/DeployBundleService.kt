package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.fge.jsonpatch.JsonPatch
import no.skatteetaten.aurora.AuroraMetrics
import no.skatteetaten.aurora.boober.facade.VaultFacade
import no.skatteetaten.aurora.boober.mapper.v1.createAuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.ConfigFieldError
import no.skatteetaten.aurora.boober.model.DeployBundle
import no.skatteetaten.aurora.boober.model.Error
import no.skatteetaten.aurora.boober.model.ValidationError
import no.skatteetaten.aurora.boober.model.VersioningError
import no.skatteetaten.aurora.boober.service.internal.Result
import no.skatteetaten.aurora.boober.service.internal.onErrorThrow
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.RevCommit
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File

@Service
class DeployBundleService(
        @Value("\${boober.docker.registry}") val dockerRegistry: String,
        val deploymentSpecValidator: AuroraDeploymentSpecValidator,
        val gitService: GitService,
        val mapper: ObjectMapper,
        val secretVaultFacade: VaultFacade,
        val metrics: AuroraMetrics) {

    private val GIT_SECRET_FOLDER = ".secret"
    private val logger = LoggerFactory.getLogger(DeployBundleService::class.java)


    fun createDeployBundle(affiliation: String, overrideFiles: List<AuroraConfigFile> = listOf()): DeployBundle {
        logger.debug("Get repo")
        val repo = getRepo(affiliation)
        logger.debug("Get all files")
        val allFilesInRepo: Map<String, Pair<RevCommit?, File>> = gitService.getAllFilesInRepo(repo)

        logger.debug("Create Aurora config")
        val auroraConfig = createAuroraConfigFromFiles(allFilesInRepo, affiliation)

        logger.debug("Get all vaults")
        val vaults = secretVaultFacade.listAllVaults(repo).associateBy { it.name }
        return DeployBundle(auroraConfig = auroraConfig, vaults = vaults, repo = repo, overrideFiles = overrideFiles)
    }

    fun <T> withDeployBundle(affiliation: String, overrideFiles: List<AuroraConfigFile> = listOf(), function: (DeployBundle) -> T): T {

        logger.debug("Create deploy bundle")
        val deployBundle = createDeployBundle(affiliation, overrideFiles)
        logger.debug("Perform op on deploy bundle")
        val res = function(deployBundle)
        logger.debug("Close and delete repo")
        gitService.closeRepository(deployBundle.repo)
        return res
    }

    fun findAuroraConfig(affiliation: String): AuroraConfig {
        val repo = getRepo(affiliation)
        val allFilesInRepo: Map<String, Pair<RevCommit?, File>> = gitService.getAllFilesInRepo(repo)
        return createAuroraConfigFromFiles(allFilesInRepo, affiliation)
    }

    fun saveAuroraConfig(auroraConfig: AuroraConfig, validateVersions: Boolean): AuroraConfig {
        return withAuroraConfig(auroraConfig.affiliation, validateVersions, {
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

    /**
     * Validates the DeployBundle for affiliation <code>affiliation</code> using the provided AuroraConfig instead
     * of the AuroraConfig already saved for that affiliation.
     */
    fun validateDeployBundleWithAuroraConfig(affiliation: String, auroraConfig: AuroraConfig): AuroraConfig {
        val deployBundle = createDeployBundle(affiliation)
        deployBundle.auroraConfig = auroraConfig
        validateDeployBundle(deployBundle)

        return auroraConfig
    }

    fun validateDeployBundle(deployBundle: DeployBundle) {

        val deploymentSpecs = tryCreateAuroraDeploymentSpecs(deployBundle, deployBundle.auroraConfig.getApplicationIds())
        deploymentSpecs.forEach {
            deploymentSpecValidator.assertIsValid(it)
        }
    }

    fun createAuroraDeploymentSpec(affiliation: String, applicationId: ApplicationId, overrides: List<AuroraConfigFile>): AuroraDeploymentSpec {
        return withDeployBundle(affiliation, overrides, { createAuroraDeploymentSpec(it, applicationId) })
    }

    fun createAuroraDeploymentSpecs(deployBundle: DeployBundle, applicationIds: List<ApplicationId>): List<AuroraDeploymentSpec> {

        return tryCreateAuroraDeploymentSpecs(deployBundle, applicationIds)
    }

    fun createAuroraDeploymentSpec(deployBundle: DeployBundle, applicationId: ApplicationId): AuroraDeploymentSpec {

        val auroraConfig = deployBundle.auroraConfig
        val overrideFiles = deployBundle.overrideFiles
        val vaults = deployBundle.vaults

        return createAuroraDeploymentSpec(auroraConfig, applicationId, dockerRegistry, overrideFiles, vaults)
    }

    private fun tryCreateAuroraDeploymentSpecs(deployBundle: DeployBundle, applicationIds: List<ApplicationId>): List<AuroraDeploymentSpec> {

        return applicationIds.map { aid ->
            try {
                val auroraDeploymentSpec: AuroraDeploymentSpec = createAuroraDeploymentSpec(deployBundle, aid)
                Result<AuroraDeploymentSpec, Error?>(value = auroraDeploymentSpec)
            } catch (e: AuroraConfigException) {
                logger.debug("ACE {}", e.errors)
                Result<AuroraDeploymentSpec, Error?>(error = ValidationError(aid.application, aid.environment, e.errors))
            } catch (e: IllegalArgumentException) {
                logger.debug("IAE {}", e.message)
                Result<AuroraDeploymentSpec, Error?>(error =
                ValidationError(aid.application, aid.environment, listOf(ConfigFieldError.illegal(e.message!!))))
            }
        }.onErrorThrow {
            logger.info("ACE {}", it)
            ValidationException("AuroraConfig contained errors for one or more applications", it)
        }
    }

    private fun withAuroraConfig(affiliation: String,
                                 validateVersions: Boolean,
                                 function: (AuroraConfig) -> AuroraConfig = { it -> it }): AuroraConfig {

        val deployBundle = createDeployBundle(affiliation)
        val auroraConfig = deployBundle.auroraConfig
        val repo = deployBundle.repo

        val newAuroraConfig = function(auroraConfig)
        deployBundle.auroraConfig = newAuroraConfig

        if (validateVersions) {
            validateGitVersion(auroraConfig, newAuroraConfig, gitService.getAllFilesInRepo(repo))
        }
        validateDeployBundle(deployBundle)
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

        return gitService.checkoutRepoForAffiliation(affiliation)
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
