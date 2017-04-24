package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfig
import no.skatteetaten.aurora.boober.model.TemplateType
import org.slf4j.Logger
import org.slf4j.LoggerFactory
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
        val openShiftClient: OpenShiftClient) {

    val logger: Logger = LoggerFactory.getLogger(SetupService::class.java)

    fun executeSetup(auroraConfig: AuroraConfig, envs: List<String>, apps: List<String>, dryRun: Boolean = false): List<ApplicationResult> {

        //∕TODO: Need to filter this somewhere on cluster
        val applicationIds: List<ApplicationId> = envs.flatMap { env -> apps.map { app -> ApplicationId(env, app) } }
        val auroraDcs: List<AuroraDeploymentConfig> = auroraConfigService.createAuroraDcsForApplications(auroraConfig, applicationIds)

        return auroraDcs.map { applyDeploymentConfig(it, dryRun) }
    }

    private fun applyDeploymentConfig(adc: AuroraDeploymentConfig, dryRun: Boolean = false): ApplicationResult {

        logger.info("Creating OpenShift objects for application ${adc.name} in namespace ${adc.namespace}")
        val openShiftObjects: List<JsonNode> = openShiftService.generateObjects(adc)
        val openShiftResponses: List<OpenShiftResponse> = openShiftClient.applyMany(adc.namespace, openShiftObjects)

        val deployResource: JsonNode? = when {
            adc.type == TemplateType.development -> openShiftService.generateBuildRequest(adc)
            adc.type == TemplateType.process -> openShiftService.generateDeploymentRequest(adc)
            adc.type == TemplateType.deploy -> openShiftResponses
                    .filter { it.kind == "imagestream" }
                    .filter { !it.changed }
                    .map { openShiftService.generateDeploymentRequest(adc) }
                    .firstOrNull()
            else -> null

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