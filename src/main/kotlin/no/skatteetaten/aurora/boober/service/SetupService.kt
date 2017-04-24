package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.controller.Overrides
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfig
import no.skatteetaten.aurora.boober.model.TemplateType.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

data class ApplicationId(
        val environmentName: String,
        val applicationName: String
) {
    override fun toString(): String {
        return "$environmentName-$applicationName"
    }
}

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
        val auroraConfigService: AuroraConfigService,
        val openShiftService: OpenShiftService,
        val openShiftClient: OpenShiftClient,
        @Value("\${openshift.cluster}") val cluster: String
) {

    val logger: Logger = LoggerFactory.getLogger(SetupService::class.java)

    fun executeSetup(auroraConfig: AuroraConfig, overrides: Overrides, envs: List<String>, apps: List<String>, dryRun: Boolean = false): List<ApplicationResult> {

        val applicationIds: List<ApplicationId> = envs.flatMap { env -> apps.map { app -> ApplicationId(env, app) } }
        val auroraDcs: List<AuroraDeploymentConfig> = auroraConfigService.createAuroraDcsForApplications(auroraConfig, applicationIds, overrides)

        return auroraDcs.filter { it.cluster == cluster }
                .map { applyDeploymentConfig(it, dryRun) }
    }

    private fun applyDeploymentConfig(adc: AuroraDeploymentConfig, dryRun: Boolean = false): ApplicationResult {

        logger.info("Creating OpenShift objects for application ${adc.name} in namespace ${adc.namespace}")
        val openShiftObjects: List<JsonNode> = openShiftService.generateObjects(adc)
        val openShiftResponses: List<OpenShiftResponse> = openShiftClient.applyMany(adc.namespace, openShiftObjects)

        val deployResource: JsonNode? = when (adc.type) {
            development -> openShiftResponses
                    .filter { it.changed }
                    .firstOrNull()?.let { openShiftService.generateBuildRequest(adc) }
            process -> openShiftService.generateDeploymentRequest(adc)
            deploy -> openShiftResponses
                    .filter { it.kind == "imagestream" && !it.changed && it.operationType == OperationType.UPDATE }
                    .map { openShiftService.generateDeploymentRequest(adc) }
                    .firstOrNull()
        }


        val finalResponse = deployResource?.let {
            openShiftResponses + openShiftClient.apply(adc.namespace, it)
        } ?: openShiftResponses


        return ApplicationResult(
                applicationId = ApplicationId(adc.envName, adc.name),
                auroraDc = adc,
                openShiftResponses = finalResponse
        )
    }
}