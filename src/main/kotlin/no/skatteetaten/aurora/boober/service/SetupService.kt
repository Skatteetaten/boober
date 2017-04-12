package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfig
import no.skatteetaten.aurora.boober.utils.Result
import no.skatteetaten.aurora.boober.utils.orElseThrow
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

data class ApplicationId(
        val environmentName: String,
        val applicationName: String
)

data class ApplicationResult(
        val applicationId: ApplicationId,
        val auroraDc: AuroraDeploymentConfig,
        val openShiftResponses: List<OpenShiftResponse> = listOf()
)

data class Error(
        val applicationId: ApplicationId,
        val messages: List<String> = listOf()
)

@Service
class SetupService(
        val auroraConfigParserService: AuroraConfigParserService,
        val openShiftService: OpenShiftService,
        val openShiftClient: OpenShiftClient) {

    val logger: Logger = LoggerFactory.getLogger(SetupService::class.java)

    fun executeSetup(auroraConfig: AuroraConfig, envs: List<String>, apps: List<String>, dryRun: Boolean = false): List<ApplicationResult> {

        //∕TODO: Need to filter this somewhere on cluster
        val applicationIds: List<ApplicationId> = envs.flatMap { env -> apps.map { app -> ApplicationId(env, app) } }
        val auroraDcs: List<AuroraDeploymentConfig> = createAuroraDcsForApplications(auroraConfig, applicationIds)

        return auroraDcs.map { applyDeploymentConfig(it, dryRun) }
    }

    fun createAuroraDcsForApplications(auroraConfig: AuroraConfig, applicationIds: List<ApplicationId>): List<AuroraDeploymentConfig> {

        val result: List<Result<AuroraDeploymentConfig?, Error?>> = applicationIds.map { aid ->
            tryResult(aid, { createAuroraDcForApplication(auroraConfig, it) })
        }

        return result.orElseThrow { AuroraConfigException("AuroraConfig contained errors for one or more applications", it) }

    }

    fun <T> tryResult(aid: ApplicationId, block: (ApplicationId) -> T): Result<T?, Error?> {
        val result: Result<T?, Error?> = try {
            Result(value = block(aid))
        } catch (e: ApplicationConfigException) {
            Result(error = Error(aid, e.errors))
        }
        return result
    }

    fun createAuroraDcForApplication(auroraConfig: AuroraConfig, aid: ApplicationId): AuroraDeploymentConfig {
        return auroraConfigParserService.createAuroraDcForApplication(auroraConfig, aid)
                .apply { validateOpenShiftReferences(this) }
    }

    /**
     * Validates that references to objects on OpenShift in the configuration are valid.
     *
     * This method should probably be extracted into its own class at some point when we add more validation,
     * like references to templates, etc.
     */
    private fun validateOpenShiftReferences(auroraDc: AuroraDeploymentConfig) {
        val errors: MutableList<String> = mutableListOf()
        auroraDc.groups
                .filter { !openShiftClient.isValidGroup(it) }
                .takeIf { it.isNotEmpty() }
                ?.let { errors.add("The following groups are not valid=${it.joinToString()}") }

        auroraDc.users
                .filter { !openShiftClient.isValidUser(it) }
                .takeIf { it.isNotEmpty() }
                ?.let { errors.add("The following users are not valid=${it.joinToString()}") }

        if (errors.isNotEmpty()) {
            throw ApplicationConfigException("Configuration contained references to one or more objects on OpenShift that does not exist", errors = errors)
        }
    }

    private fun applyDeploymentConfig(adc: AuroraDeploymentConfig, dryRun: Boolean = false): ApplicationResult {

        logger.info("Creating OpenShift objects for application ${adc.name} in namespace ${adc.namespace}")
        val openShiftObjects: List<JsonNode> = openShiftService.generateObjects(adc)
        val openShiftResponses: List<OpenShiftResponse> = openShiftClient.applyMany(adc.namespace, openShiftObjects, dryRun)

        return ApplicationResult(
                applicationId = ApplicationId(adc.envName, adc.name),
                auroraDc = adc,
                openShiftResponses = openShiftResponses
        )
    }
}