package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import org.springframework.stereotype.Service

@Service
class RedeployService(val openShiftClient: OpenShiftClient, val openShiftObjectGenerator: OpenShiftObjectGenerator) {

    data class ImageInformation(val lastTriggeredImage: String, val imageStreamName: String, val imageStreamTag: String)

    data class VerificationResult(val success: Boolean = true, val message: String? = null)

    data class RedeployResult @JvmOverloads constructor(
            val openShiftResponses: List<OpenShiftResponse> = listOf(),
            val success: Boolean = true,
            val message: String? = null) {

        companion object {
            fun fromOpenShiftResponses(openShiftResponses: List<OpenShiftResponse>): RedeployResult {
                val success = openShiftResponses?.all { it.success }
                val message = if (success) "Redeploy succeeded" else "Redeploy failed"
                return RedeployResult(openShiftResponses = openShiftResponses, success = success, message = message)
            }
        }
    }

    fun triggerRedeploy(deploymentSpec: AuroraDeploymentSpec, redeployContext: RedeployContext): RedeployResult {
        val type = deploymentSpec.type
        if (type == TemplateType.build || type == TemplateType.development) {
            return RedeployResult(message = "No deploy was made with $type type")
        }

        return if (redeployContext.isDeploymentRequest()) {
            requestDeployment(deploymentSpec)
        } else {
            importImageStream(deploymentSpec, redeployContext)
        }
    }

    private fun requestDeployment(deploymentSpec: AuroraDeploymentSpec) =
            RedeployResult.fromOpenShiftResponses(listOf(performDeploymentRequestCommand(deploymentSpec)))

    private fun importImageStream(deploymentSpec: AuroraDeploymentSpec, redeployContext: RedeployContext): RedeployResult {
        val imageStreamImportResource = generateImageStreamImportResource(redeployContext)
                ?: return RedeployResult()
        val imageStreamImportResponse = performImageStreamImportCommand(deploymentSpec, imageStreamImportResource)
        redeployContext.verifyResponse(imageStreamImportResponse).takeUnless { it.success }?.let {
            return RedeployResult(success = false, message = it.message, openShiftResponses = listOf(imageStreamImportResponse))
        }

        if (redeployContext.noImageStreamImportRequired(imageStreamImportResponse)) {
            return RedeployResult.fromOpenShiftResponses(listOf(imageStreamImportResponse))
        }

        val deploymentRequestResponse = performDeploymentRequestCommand(deploymentSpec)
        return RedeployResult.fromOpenShiftResponses(listOf(imageStreamImportResponse, deploymentRequestResponse))
    }

    fun generateImageStreamImportResource(redeployContext: RedeployContext): JsonNode? {
        val imageInformation = redeployContext.findImageInformation()
        val imageName = redeployContext.findImageName()
        if (imageInformation != null && imageName != null) {
            return openShiftObjectGenerator.generateImageStreamImport(imageInformation.imageStreamName, imageName)
        }

        return null
    }

    private fun performImageStreamImportCommand(deploymentSpec: AuroraDeploymentSpec, redeployResource: JsonNode): OpenShiftResponse {
        val namespace = deploymentSpec.environment.namespace
        val command = openShiftClient.createOpenShiftCommand(namespace, redeployResource)
        return try {
            openShiftClient.performOpenShiftCommand(namespace, command)
        } catch (e: OpenShiftException) {
            OpenShiftResponse.fromOpenShiftException(e, command)
        }
    }

    private fun performDeploymentRequestCommand(deploymentSpec: AuroraDeploymentSpec): OpenShiftResponse {
        val namespace = deploymentSpec.environment.namespace
        val command = openShiftClient.createOpenShiftCommand(namespace,
                openShiftObjectGenerator.generateDeploymentRequest(deploymentSpec.name))
        return try {
            openShiftClient.performOpenShiftCommand(namespace, command)
        } catch (e: OpenShiftException) {
            OpenShiftResponse.fromOpenShiftException(e, command)
        }
    }
}