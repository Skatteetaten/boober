package no.skatteetaten.aurora.boober.service

import kotlinx.coroutines.experimental.ThreadPoolDispatcher
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.newFixedThreadPoolContext
import kotlinx.coroutines.experimental.runBlocking
import no.skatteetaten.aurora.boober.mapper.v1.createAuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.util.StopWatch

class AuroraConfigWithOverrides(
        var auroraConfig: AuroraConfig,
        val overrideFiles: List<AuroraConfigFile> = listOf()
)

@Service
class DeploymentSpecService(
        val auroraConfigService: AuroraConfigService,
        val deploymentSpecValidator: AuroraDeploymentSpecValidator,
        @Value("\${boober.validationPoolSize:6}") val validationPoolSize: Int) {

    private val dispatcher: ThreadPoolDispatcher = newFixedThreadPoolContext(validationPoolSize, "validationPool")

    private val logger = LoggerFactory.getLogger(DeploymentSpecService::class.java)


    fun createValidatedAuroraDeploymentSpecs(auroraConfigName: String, applicationIds: List<ApplicationId>, overrideFiles: List<AuroraConfigFile> = listOf()): List<AuroraDeploymentSpec> {

        val auroraConfig = auroraConfigService.findAuroraConfig(auroraConfigName)
        return createValidatedAuroraDeploymentSpecs(AuroraConfigWithOverrides(auroraConfig, overrideFiles), applicationIds)
    }

    fun validateAuroraConfig(auroraConfigName: String, overrideFiles: List<AuroraConfigFile> = listOf()) {
        val auroraConfig = auroraConfigService.findAuroraConfig(auroraConfigName)
        validateAuroraConfig(auroraConfig, overrideFiles)
    }

    fun validateAuroraConfig(auroraConfig: AuroraConfig, overrideFiles: List<AuroraConfigFile> = listOf()) {
        val applicationIds = auroraConfig.getApplicationIds()
        createValidatedAuroraDeploymentSpecs(AuroraConfigWithOverrides(auroraConfig, overrideFiles), applicationIds)
    }

    private fun createValidatedAuroraDeploymentSpecs(auroraConfigWithOverrides: AuroraConfigWithOverrides, applicationIds: List<ApplicationId>): List<AuroraDeploymentSpec> {

        val stopWatch = StopWatch().apply { start() }
        val specs: List<AuroraDeploymentSpec> = runBlocking(dispatcher) {
            applicationIds.map { aid ->
                async(dispatcher) {
                    try {
                        val spec = createValidatedAuroraDeploymentSpec(auroraConfigWithOverrides, aid)
                        Pair<AuroraDeploymentSpec?, ExceptionWrapper?>(first = spec, second = null)
                    } catch (e: Throwable) {
                        Pair<AuroraDeploymentSpec?, ExceptionWrapper?>(first = null, second = ExceptionWrapper(aid, e))
                    }
                }
            }
                    .map { it.await() }
        }.onErrorThrow(::MultiApplicationValidationException)
        stopWatch.stop()
        logger.debug("Validated AuroraConfig ${auroraConfigWithOverrides.auroraConfig.affiliation} with ${applicationIds.size} applications in ${stopWatch.totalTimeMillis} millis")
        return specs
    }

    private fun createValidatedAuroraDeploymentSpec(auroraConfigWithOverrides: AuroraConfigWithOverrides, aid: ApplicationId): AuroraDeploymentSpec {

        val stopWatch = StopWatch().apply { start() }
        val spec = createAuroraDeploymentSpec(auroraConfigWithOverrides, aid)
        deploymentSpecValidator.assertIsValid(spec)
        stopWatch.stop()

        logger.debug("Created ADC for app=${aid.application}, env=${aid.environment} in ${stopWatch.totalTimeMillis} millis")

        return spec
    }

    private fun createAuroraDeploymentSpec(auroraConfigWithOverrides: AuroraConfigWithOverrides, aid: ApplicationId): AuroraDeploymentSpec {

        val auroraConfig = auroraConfigWithOverrides.auroraConfig
        val overrideFiles = auroraConfigWithOverrides.overrideFiles

        return createAuroraDeploymentSpec(auroraConfig, aid, overrideFiles)
    }
}
