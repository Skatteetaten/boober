package no.skatteetaten.aurora.boober.service

import io.fabric8.openshift.api.model.DeploymentConfig
import io.fabric8.openshift.api.model.ImageStream
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.service.internal.ImageStreamImportGenerator
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import no.skatteetaten.aurora.boober.service.openshift.resource
import no.skatteetaten.aurora.boober.utils.deploymentConfigFromJson
import no.skatteetaten.aurora.boober.utils.findCurrentImageHash
import no.skatteetaten.aurora.boober.utils.findDockerImageUrl
import no.skatteetaten.aurora.boober.utils.findImageChangeTriggerTagName
import no.skatteetaten.aurora.boober.utils.imageStreamFromJson
import no.skatteetaten.aurora.boober.utils.imageStreamImportFromJson
import org.springframework.stereotype.Service

@Service
class RedeployService(
    val openShiftClient: OpenShiftClient,
    val openShiftObjectGenerator: OpenShiftObjectGenerator
) {

    data class RedeployResult @JvmOverloads constructor(
        val openShiftResponses: List<OpenShiftResponse> = listOf(),
        val success: Boolean = true,
        val message: String? = null
    ) {

        companion object {
            fun fromOpenShiftResponses(openShiftResponses: List<OpenShiftResponse>): RedeployResult {
                val success = openShiftResponses.all { it.success }
                val message = if (success) "Redeploy succeeded" else "Redeploy failed"
                return RedeployResult(openShiftResponses = openShiftResponses, success = success, message = message)
            }
        }
    }

    fun triggerRedeploy(
        openShiftResponses: List<OpenShiftResponse>,
        type: TemplateType
    ): RedeployResult {

        if (type == TemplateType.development) {
            return RedeployResult(message = "No explicit deploy was made with $type type")
        }

        val isResource = openShiftResponses.resource("imagestream")
        val imageStream = isResource?.responseBody?.let { imageStreamFromJson(it) }

        val dcResource = openShiftResponses.resource("deploymentconfig")
        val oldDcResource = dcResource?.command?.previous?.let { deploymentConfigFromJson(it) }
        val wasPaused = oldDcResource?.spec?.replicas == 0

        val deploymentConfig = dcResource?.responseBody?.let { deploymentConfigFromJson(it) }
            ?: throw IllegalArgumentException("Missing DeploymentConfig")

        if (isResource?.command?.operationType == OperationType.CREATE) {
            return RedeployResult(message = "No explicit deploy was made for newly created imagestream")
        }
        val imageChangeTriggerTagName = deploymentConfig.findImageChangeTriggerTagName()
        if (imageStream == null || imageChangeTriggerTagName == null) {
            return triggerRedeploy(deploymentConfig)
        }
        return triggerRedeploy(imageStream, deploymentConfig.metadata.name, imageChangeTriggerTagName, wasPaused)
    }

    fun triggerRedeploy(deploymentConfig: DeploymentConfig): RedeployResult {
        val namespace = deploymentConfig.metadata.namespace
        val name = deploymentConfig.metadata.name
        val deploymentRequestResponse = performDeploymentRequest(namespace, name)
        return RedeployResult.fromOpenShiftResponses(listOf(deploymentRequestResponse))
    }

    fun triggerRedeploy(imageStream: ImageStream, dcName: String, tagName: String, wasPaused: Boolean): RedeployResult {
        val namespace = imageStream.metadata.namespace
        val isName = imageStream.metadata.name
        val dockerImageUrl = imageStream.findDockerImageUrl(tagName)
            ?: throw IllegalArgumentException("Missing docker image url")
        val imageStreamImportResponse = performImageStreamImport(namespace, dockerImageUrl, isName)
        if (!imageStreamImportResponse.success) {
            return createFailedRedeployResult(imageStreamImportResponse.exception, imageStreamImportResponse)
        }

        val imageStreamImport = imageStreamImportFromJson(imageStreamImportResponse.responseBody)
        imageStreamImport.findErrorMessage(tagName)
            // TODO: bør vi ha en beskjed her?
            ?.let { return createFailedRedeployResult(it, imageStreamImportResponse) }

        if (imageStreamImport.isDifferentImage(imageStream.findCurrentImageHash())) {
            // TODO: bør vi ha en beskjed her?
            return RedeployResult.fromOpenShiftResponses(listOf(imageStreamImportResponse))
        }
        if (wasPaused) {
            // TODO: bør vi ha en beskjed her?
            return RedeployResult.fromOpenShiftResponses(listOf(imageStreamImportResponse))
        }
        val deploymentRequestResponse = performDeploymentRequest(namespace, dcName)
        return RedeployResult.fromOpenShiftResponses(listOf(imageStreamImportResponse, deploymentRequestResponse))
    }

    private fun createFailedRedeployResult(message: String?, openShiftResponse: OpenShiftResponse) =
        RedeployResult(success = false, message = message, openShiftResponses = listOf(openShiftResponse))

    private fun performImageStreamImport(
        namespace: String,
        dockerImageUrl: String,
        imageStreamName: String
    ): OpenShiftResponse {
        val imageStreamImport = ImageStreamImportGenerator.create(dockerImageUrl, imageStreamName)
        val command = OpenshiftCommand(OperationType.CREATE, imageStreamImport.toJsonNode())
        return openShiftClient.performOpenShiftCommand(namespace, command)
    }

    private fun performDeploymentRequest(namespace: String, name: String): OpenShiftResponse {
        val deploymentRequest = openShiftObjectGenerator.generateDeploymentRequest(name)
        val command = OpenshiftCommand(OperationType.CREATE, deploymentRequest)
        return openShiftClient.performOpenShiftCommand(namespace, command)
    }
}