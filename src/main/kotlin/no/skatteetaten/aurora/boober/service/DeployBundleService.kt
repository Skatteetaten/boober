package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.fge.jsonpatch.JsonPatch
import no.skatteetaten.aurora.AuroraMetrics
import no.skatteetaten.aurora.boober.facade.VaultFacade
import no.skatteetaten.aurora.boober.mapper.v1.createAuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.*
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


    fun <T> withDeployBundle(affiliation: String, overrideFiles: List<AuroraConfigFile> = listOf(), function: (Git, DeployBundle) -> T): T {

        logger.debug("Get repo")
        val repo = getRepo(affiliation)
        //This is when we deploy

        logger.debug("Get all aurora config files")
        val auroraConfigFiles = gitService.getAllAuroraConfigFiles(repo).map {
            AuroraConfigFile(it.key, mapper.readValue(it.value))
        }

        val auroraConfig = AuroraConfig(auroraConfigFiles = auroraConfigFiles, affiliation = affiliation)

        logger.debug("Get all vaults")
        val vaults = secretVaultFacade.listAllVaults(repo).associateBy { it.name }
        logger.debug("Create deploy bundle")
        val deployBundle = DeployBundle(auroraConfig = auroraConfig, vaults = vaults, overrideFiles = overrideFiles)
        logger.debug("Perform op on deploy bundle")
        val res = function(repo, deployBundle)
        logger.debug("Close and delete repo")
        gitService.closeRepository(repo)
        return res
    }

    fun findAuroraConfig(affiliation: String): AuroraConfig {
        logger.debug("Find aurora config")
        val repo = getRepo(affiliation)
        //TODO: add revCommit
        logger.debug("get all files with revCommit")
        val allFilesInRepo: Map<String, Pair<RevCommit?, File>> = gitService.getAllFilesInRepo(repo)
        logger.debug("create aurora config from files")
        val res = createAuroraConfigFromFiles(allFilesInRepo, affiliation)
        gitService.closeRepository(repo)
        logger.debug("/Find aurora config")
        return res
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

        val repo = getRepo(affiliation)
        val vaults = secretVaultFacade.listAllVaults(repo).associateBy { it.name }
        val bundle = DeployBundle(auroraConfig = auroraConfig, vaults = vaults)
        try {
            validateDeployBundle(bundle)
        } finally {
            gitService.closeRepository(repo)
        }

        return auroraConfig
    }

    fun validateDeployBundle(deployBundle: DeployBundle) {

        val deploymentSpecs = tryCreateAuroraDeploymentSpecs(deployBundle, deployBundle.auroraConfig.getApplicationIds())
        deploymentSpecs.forEach {
            deploymentSpecValidator.assertIsValid(it)
        }
    }

    fun createAuroraDeploymentSpec(affiliation: String, applicationId: ApplicationId, overrides: List<AuroraConfigFile>): AuroraDeploymentSpec {
        return withDeployBundle(affiliation, overrides) { _, bundle ->
            createAuroraDeploymentSpec(bundle, applicationId)
        }
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

        logger.debug("withAuroraConfig")
        val repo = getRepo(affiliation)

        logger.debug("Get all files")
        val allFilesInRepo: Map<String, Pair<RevCommit?, File>> = gitService.getAllFilesInRepo(repo)
        logger.debug("Create Aurora config")
        val auroraConfig = createAuroraConfigFromFiles(allFilesInRepo, affiliation)
        logger.debug("/Create Aurora config")
        val newAuroraConfig = function(auroraConfig)

        if (validateVersions) {
            logger.debug("validate git version")
            validateGitVersion(auroraConfig, newAuroraConfig, allFilesInRepo)
            logger.debug("/validate git version")
        }

        logger.debug("list all vaults")
        val vaults = secretVaultFacade.listAllVaults(repo).associateBy { it.name }
        logger.debug("/list all vaults")

        val deployBundle = DeployBundle(auroraConfig = newAuroraConfig, vaults = vaults)
        logger.debug("validate deploy bundle")
        validateDeployBundle(deployBundle)
        logger.debug("/validate deploy bundle")

        logger.debug("commit Aurora config")
        commitAuroraConfig(repo, newAuroraConfig)
        logger.debug("/commit Aurora config")

        logger.debug("/withAuroraConfig")
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
                .map { AuroraConfigFile(it.key, mapper.readValue(it.value.second), version = it.value.first?.abbreviate(7)?.name()) }

        return AuroraConfig(auroraConfigFiles = auroraConfigFiles, affiliation = affiliation)
    }


    private fun commitAuroraConfig(repo: Git,
                                   newAuroraConfig: AuroraConfig) {

        logger.debug("create file map")
        val configFiles: Map<String, String> = newAuroraConfig.auroraConfigFiles.map {
            it.name to mapper.writerWithDefaultPrettyPrinter().writeValueAsString(it.contents)
        }.toMap()


        //when we save auroraConfig we do not want to mess with secret vault files
        val keep: (String) -> Boolean = { it -> !it.startsWith(GIT_SECRET_FOLDER) }

        logger.debug("save files and close")
        gitService.saveFilesAndClose(repo, configFiles, keep)
    }

    fun findAuroraConfigFile(affiliation: String, fileName: String): AuroraConfigFile? {
        logger.debug("Find aurora config filename={}", fileName)
        val repo = getRepo(affiliation)
        val file = gitService.getFile(repo, fileName)
        val jsonFile = file?.let {
            AuroraConfigFile(it.path, mapper.readValue(it.file), version = it.commit?.abbreviate(7)?.name())
        }
        gitService.closeRepository(repo)
        logger.debug("/Find aurora config file")
        return jsonFile


    }

    fun findAuroraConfigFileNames(affiliation: String): List<String> {
        val repo = getRepo(affiliation)
        val files = gitService.getAllAuroraConfigFiles(repo).map { it.key }
        gitService.closeRepository(repo)
        return files

    }
}
