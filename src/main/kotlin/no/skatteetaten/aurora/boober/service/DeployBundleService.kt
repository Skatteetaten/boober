package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.fge.jsonpatch.JsonPatch
import kotlinx.coroutines.experimental.ThreadPoolDispatcher
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.newFixedThreadPoolContext
import kotlinx.coroutines.experimental.runBlocking
import no.skatteetaten.aurora.AuroraMetrics
import no.skatteetaten.aurora.boober.mapper.v1.createAuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.RevCommit
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.util.StopWatch
import java.io.File

@Service
class DeployBundleService(
        val deploymentSpecValidator: AuroraDeploymentSpecValidator,
        val gitService: GitService,
        val mapper: ObjectMapper,
        val secretVaultService: VaultService,
        val metrics: AuroraMetrics,
        @Value("\${boober.validationPoolSize:6}") val validationPoolSize: Int) {

    private val dispatcher: ThreadPoolDispatcher = newFixedThreadPoolContext(validationPoolSize, "validationPool")

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
        val vaults = secretVaultService.findAllVaultsInVaultCollection(affiliation).associateBy { it.name }
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
        val allFilesInRepo: Map<String, Pair<RevCommit?, File>> = gitService.getAllFiles(repo).mapValues {
            val commit = gitService.getRevCommit(repo, it.key)
            Pair(commit, it.value)
        }
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
        val vaults = secretVaultService.findAllVaultsInVaultCollection(affiliation).associateBy { it.name }
        val bundle = DeployBundle(auroraConfig = auroraConfig, vaults = vaults)
        try {
            validateDeployBundle(bundle)
        } finally {
            gitService.closeRepository(repo)
        }

        return auroraConfig
    }

    fun validateDeployBundle(deployBundle: DeployBundle) {

        createValidatedAuroraDeploymentSpecs(deployBundle)
    }

    fun createValidatedAuroraDeploymentSpecs(deployBundle: DeployBundle): List<AuroraDeploymentSpec> {
        val applicationIds = deployBundle.auroraConfig.getApplicationIds()
        return createValidatedAuroraDeploymentSpecs(deployBundle, applicationIds)
    }

    fun createValidatedAuroraDeploymentSpecs(deployBundle: DeployBundle, applicationIds: List<ApplicationId>): List<AuroraDeploymentSpec> {

        val stopWatch = StopWatch().apply { start() }
        val specs: List<AuroraDeploymentSpec> = runBlocking(dispatcher) {
            applicationIds.map { aid ->
                async(dispatcher) {
                    try {
                        val spec = createValidatedAuroraDeploymentSpec(deployBundle, aid)
                        Pair<AuroraDeploymentSpec?, ExceptionWrapper?>(first = spec, second = null)
                    } catch (e: Throwable) {
                        Pair<AuroraDeploymentSpec?, ExceptionWrapper?>(first = null, second = ExceptionWrapper(aid, e))
                    }
                }
            }
                    .map { it.await() }
        }.onErrorThrow(::MultiApplicationValidationException)
        stopWatch.stop()
        logger.debug("Created validated DeployBundle for AuroraConfig ${deployBundle.auroraConfig.affiliation} with ${applicationIds.size} applications in ${stopWatch.totalTimeMillis} millis")
        return specs
    }

    fun createValidatedAuroraDeploymentSpec(deployBundle: DeployBundle, aid: ApplicationId): AuroraDeploymentSpec {

        val stopWatch = StopWatch().apply { start() }
        val spec = createAuroraDeploymentSpec(deployBundle, aid)
        deploymentSpecValidator.assertIsValid(spec)
        stopWatch.stop()

        logger.debug("Created ADC for app=${aid.application}, env=${aid.environment} in ${stopWatch.totalTimeMillis} millis")

        return spec
    }


    fun createAuroraDeploymentSpec(affiliation: String, applicationId: ApplicationId, overrides: List<AuroraConfigFile>): AuroraDeploymentSpec {
        return withDeployBundle(affiliation, overrides) { _, bundle ->
            createAuroraDeploymentSpec(bundle, applicationId)
        }
    }

    fun createAuroraDeploymentSpecs(deployBundle: DeployBundle, applicationIds: List<ApplicationId>): List<AuroraDeploymentSpec> {

        return applicationIds.map { aid ->
            tryCreateAuroraDeploymentSpec(deployBundle, aid)
        }.onErrorThrow(::MultiApplicationValidationException)
    }

    fun createAuroraDeploymentSpec(deployBundle: DeployBundle, aid: ApplicationId): AuroraDeploymentSpec {

        val auroraConfig = deployBundle.auroraConfig
        val overrideFiles = deployBundle.overrideFiles
        val vaults = deployBundle.vaults

        return createAuroraDeploymentSpec(auroraConfig, aid, overrideFiles, vaults)
    }

    private fun tryCreateAuroraDeploymentSpec(deployBundle: DeployBundle, aid: ApplicationId): Pair<AuroraDeploymentSpec?, ExceptionWrapper?> {

        return try {
            val auroraDeploymentSpec: AuroraDeploymentSpec = createAuroraDeploymentSpec(deployBundle, aid)
            Pair<AuroraDeploymentSpec?, ExceptionWrapper?>(first = auroraDeploymentSpec, second = null)
        } catch (e: Throwable) {
            Pair<AuroraDeploymentSpec?, ExceptionWrapper?>(first = null, second = ExceptionWrapper(aid, e))
        }
    }

    private fun withAuroraConfig(affiliation: String,
                                 validateVersions: Boolean,
                                 function: (AuroraConfig) -> AuroraConfig = { it -> it }): AuroraConfig {

        logger.debug("withAuroraConfig")
        val repo = getRepo(affiliation)

        logger.debug("Get all files")
        val allFilesInRepo: Map<String, Pair<RevCommit?, File>> = gitService.getAllFiles(repo).mapValues {
            val commit = gitService.getRevCommit(repo, it.key)
            Pair(commit, it.value)
        }
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
        val vaults = secretVaultService.findAllVaultsInVaultCollection(affiliation).associateBy { it.name }
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

        return gitService.checkoutRepository(affiliation)
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
            AuroraConfigFile(it.path, mapper.readValue(it)/*, version = it.commit?.abbreviate(7)?.name()*/)
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
