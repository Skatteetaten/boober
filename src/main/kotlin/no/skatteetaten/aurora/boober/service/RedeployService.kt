package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
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

    fun triggerRedeploy(redeployContext: RedeployContext): RedeployResult {
        redeployContext.generateRedeployResource()
        if(redeployContext::redeployResource.isInitialized) {

        }

        val imageStreamImportResponse = runImageStreamImport(redeployContext)

        redeployContext.verifyResponse(imageStreamImportResponse).takeUnless { it.success }?.let {
            return RedeployResult(success = false, message = it.message, openShiftResponses = listOf(imageStreamImportResponse))
        }

        if (redeployContext.didNotImportImageStream(imageStreamImportResponse)) {
            return RedeployResult.fromOpenShiftResponses(listOf(imageStreamImportResponse))
        }

        val deploymentRequestResponse = runDeploymentRequest(redeployContext.deploymentSpec)
        return RedeployResult.fromOpenShiftResponses(listOf(imageStreamImportResponse, deploymentRequestResponse))
    }

    private fun runImageStreamImport(redeployContext: RedeployContext): OpenShiftResponse {
        val namespace = redeployContext.deploymentSpec.environment.namespace
        val command = openShiftClient.createOpenShiftCommand(namespace, redeployContext.redeployResource)
        return try {
            openShiftClient.performOpenShiftCommand(namespace, command)
        } catch (e: OpenShiftException) {
            OpenShiftResponse.fromOpenShiftException(e, command)
        }
    }

    private fun runDeploymentRequest(deploymentSpec: AuroraDeploymentSpec): OpenShiftResponse {
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