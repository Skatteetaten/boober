package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfig
import no.skatteetaten.aurora.boober.model.TemplateType.*
import no.skatteetaten.aurora.boober.service.internal.ApplicationResult
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class SetupService(
        val auroraDeploymentConfigService: AuroraDeploymentConfigService,
        val openShiftService: OpenShiftService,
        val openShiftClient: OpenShiftClient,
        @Value("\${openshift.cluster}") val cluster: String) {

    val logger: Logger = LoggerFactory.getLogger(SetupService::class.java)

    fun executeSetup(auroraConfig: AuroraConfig, applicationIds: List<ApplicationId>,
                     overrides: List<AuroraConfigFile>): List<ApplicationResult> {

        val appIds: List<ApplicationId> = applicationIds
                .takeIf { it.isNotEmpty() } ?: auroraConfig.getApplicationIds()

        val auroraDcs = auroraDeploymentConfigService.createAuroraDcs(auroraConfig, appIds, overrides)

        return auroraDcs.filter { it.cluster == cluster }
                .map { applyDeploymentConfig(it) }
    }

    private fun applyDeploymentConfig(adc: AuroraDeploymentConfig): ApplicationResult {

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
