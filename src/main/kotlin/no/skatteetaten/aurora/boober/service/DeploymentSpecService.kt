package no.skatteetaten.aurora.boober.service

import kotlinx.coroutines.experimental.ThreadPoolDispatcher
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.newFixedThreadPoolContext
import kotlinx.coroutines.experimental.runBlocking
import no.skatteetaten.aurora.boober.mapper.v1.createAuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.DeployBundle
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.util.StopWatch

@Service
class DeploymentSpecService(
        val auroraConfigService: AuroraConfigService,
        val deploymentSpecValidator: AuroraDeploymentSpecValidator,
        @Value("\${boober.validationPoolSize:6}") val validationPoolSize: Int) {

    private val dispatcher: ThreadPoolDispatcher = newFixedThreadPoolContext(validationPoolSize, "validationPool")

    private val logger = LoggerFactory.getLogger(DeploymentSpecService::class.java)


    fun createValidatedAuroraDeploymentSpecs(auroraConfigName: String, applicationIds: List<ApplicationId>, overrideFiles: List<AuroraConfigFile> = listOf()): List<AuroraDeploymentSpec> {

        val auroraConfig = auroraConfigService.findAuroraConfig(auroraConfigName)
        return createValidatedAuroraDeploymentSpecs(DeployBundle(auroraConfig, overrideFiles), applicationIds)
    }

    fun createValidatedAuroraDeploymentSpecs(auroraConfigName: String, overrideFiles: List<AuroraConfigFile> = listOf()): List<AuroraDeploymentSpec> {
        val auroraConfig = auroraConfigService.findAuroraConfig(auroraConfigName)
        val applicationIds = auroraConfig.getApplicationIds()
        return createValidatedAuroraDeploymentSpecs(DeployBundle(auroraConfig, overrideFiles), applicationIds)
    }

    private fun createValidatedAuroraDeploymentSpecs(deployBundle: DeployBundle, applicationIds: List<ApplicationId>): List<AuroraDeploymentSpec> {

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

    private fun createValidatedAuroraDeploymentSpec(deployBundle: DeployBundle, aid: ApplicationId): AuroraDeploymentSpec {

        val stopWatch = StopWatch().apply { start() }
        val spec = createAuroraDeploymentSpec(deployBundle, aid)
        deploymentSpecValidator.assertIsValid(spec)
        stopWatch.stop()

        logger.debug("Created ADC for app=${aid.application}, env=${aid.environment} in ${stopWatch.totalTimeMillis} millis")

        return spec
    }

    private fun createAuroraDeploymentSpec(deployBundle: DeployBundle, aid: ApplicationId): AuroraDeploymentSpec {

        val auroraConfig = deployBundle.auroraConfig
        val overrideFiles = deployBundle.overrideFiles

        return createAuroraDeploymentSpec(auroraConfig, aid, overrideFiles)
    }
}
