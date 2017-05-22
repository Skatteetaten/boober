package no.skatteetaten.aurora.boober.facade

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.model.TemplateType.development
import no.skatteetaten.aurora.boober.service.AuroraConfigValidationService
import no.skatteetaten.aurora.boober.service.OpenShiftObjectGenerator
import no.skatteetaten.aurora.boober.service.internal.ApplicationResult
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class SetupFacade(
        val auroraConfigValidationService: AuroraConfigValidationService,
        val openShiftObjectGenerator: OpenShiftObjectGenerator,
        val openShiftClient: OpenShiftClient,
        @Value("\${openshift.cluster}") val cluster: String) {

    val logger: Logger = LoggerFactory.getLogger(SetupFacade::class.java)

    fun executeSetup(auroraConfig: AuroraConfig, applicationIds: List<ApplicationId>,
                     overrides: List<AuroraConfigFile>): List<ApplicationResult> {

        val appIds: List<ApplicationId> = applicationIds
                .takeIf { it.isNotEmpty() } ?: auroraConfig.getApplicationIds()

        val auroraDcs = auroraConfigValidationService.createAuroraDcs(auroraConfig, appIds, overrides)

        return auroraDcs
                .filter { it.cluster == cluster }
                .map { applyDeploymentConfig(it) }

    }

    private fun applyDeploymentConfig(adc: AuroraDeploymentConfig): ApplicationResult {
        logger.info("Creating OpenShift objects for application ${adc.name} in namespace ${adc.namespace}")

        val openShiftObjects = openShiftObjectGenerator.generateObjects(adc)

        val openShiftResponses: List<OpenShiftResponse> = openShiftClient.applyMany(adc.namespace, openShiftObjects)

        val deployResource: JsonNode? =
                generateRedeployResource(openShiftResponses, adc)

        val finalResponse = deployResource?.let {
            openShiftResponses + openShiftClient.apply(adc.namespace, it)
        } ?: openShiftResponses

        return ApplicationResult(
                applicationId = ApplicationId(adc.envName, adc.name),
                auroraDc = adc,
                openShiftResponses = finalResponse
        )
    }

    fun generateRedeployResource(openShiftResponses: List<OpenShiftResponse>, adc: AuroraDeploymentConfig): JsonNode? {
        val imageStream = openShiftResponses.find { it.kind == "imagestream" }

        val deployResource: JsonNode? =
                if (adc.type == development) {
                    openShiftResponses.filter { it.changed }.firstOrNull()?.let {
                        openShiftObjectGenerator.generateBuildRequest(adc as AuroraDeploymentConfigDeploy)
                    }
                } else if (imageStream == null) {
                    openShiftObjectGenerator.generateDeploymentRequest(adc)
                } else if (!imageStream.changed && imageStream.operationType == OperationType.UPDATE) {
                    openShiftObjectGenerator.generateDeploymentRequest(adc)
                } else {
                    null
                }
        return deployResource
    }
}
