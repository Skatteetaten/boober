package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.experimental.ThreadPoolDispatcher
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.newFixedThreadPoolContext
import kotlinx.coroutines.experimental.runBlocking
import no.skatteetaten.aurora.AuroraMetrics
import no.skatteetaten.aurora.boober.mapper.v1.createAuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.*
import org.eclipse.jgit.api.Git
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.util.StopWatch

@Service
class DeployBundleService(
        val deploymentSpecValidator: AuroraDeploymentSpecValidator,
        val gitService: GitService,
        val mapper: ObjectMapper,
        val secretVaultService: VaultService,
        val metrics: AuroraMetrics,
        @Value("\${boober.validationPoolSize:6}") val validationPoolSize: Int) {

    private val dispatcher: ThreadPoolDispatcher = newFixedThreadPoolContext(validationPoolSize, "validationPool")

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

    /**
     * Validates the DeployBundle for affiliation <code>affiliation</code> using the provided AuroraConfig instead
     * of the AuroraConfig already saved for that affiliation.
     */
/*
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
*/

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

        return createAuroraDeploymentSpec(auroraConfig, aid, overrideFiles)
    }

    private fun tryCreateAuroraDeploymentSpec(deployBundle: DeployBundle, aid: ApplicationId): Pair<AuroraDeploymentSpec?, ExceptionWrapper?> {

        return try {
            val auroraDeploymentSpec: AuroraDeploymentSpec = createAuroraDeploymentSpec(deployBundle, aid)
            Pair<AuroraDeploymentSpec?, ExceptionWrapper?>(first = auroraDeploymentSpec, second = null)
        } catch (e: Throwable) {
            Pair<AuroraDeploymentSpec?, ExceptionWrapper?>(first = null, second = ExceptionWrapper(aid, e))
        }
    }

    private fun getRepo(affiliation: String): Git {

        return gitService.checkoutRepository(affiliation)
    }
}
