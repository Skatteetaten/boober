package no.skatteetaten.aurora.boober.service

import io.fabric8.openshift.api.model.DeploymentConfig
import io.fabric8.openshift.api.model.ImageStream
import no.skatteetaten.aurora.boober.service.internal.ImageStreamImportGenerator
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import no.skatteetaten.aurora.boober.utils.findCurrentImageHash
import no.skatteetaten.aurora.boober.utils.findImageChangeTriggerTagName
import no.skatteetaten.aurora.boober.utils.imageStreamImportFromJson
import org.springframework.stereotype.Service

@Service
class RedeployService(val openShiftClient: OpenShiftClient,
                      val openShiftObjectGenerator: OpenShiftObjectGenerator) {

    data class RedeployResult @JvmOverloads constructor(
            val openShiftResponses: List<OpenShiftResponse> = listOf(),
            val success: Boolean = true,
            val message: String? = null) {

        companion object {
            fun fromOpenShiftResponses(openShiftResponses: List<OpenShiftResponse>): RedeployResult {
                val success = openShiftResponses.all { it.success }
                val message = if (success) "Redeploy succeeded" else "Redeploy failed"
                return RedeployResult(openShiftResponses = openShiftResponses, success = success, message = message)
            }
        }
    }

    fun triggerRedeploy(deploymentConfig: DeploymentConfig, imageStream: ImageStream?): RedeployResult {
        val namespace = deploymentConfig.metadata.namespace
        val name = deploymentConfig.metadata.name
        val imageChangeTriggerTagName = deploymentConfig.findImageChangeTriggerTagName()
                ?: throw IllegalArgumentException("No imageChangeTriggerName found")

        return if (imageStream == null) {
            requestDeployment(namespace, name)
        } else {
            rolloutDeployment(namespace, name, imageChangeTriggerTagName, imageStream)
        }
    }

    fun requestDeployment(namespace: String, name: String): RedeployResult {
        val deploymentRequestResponse = performDeploymentRequest(namespace, name)
        return RedeployResult.fromOpenShiftResponses(listOf(deploymentRequestResponse))
    }

    fun rolloutDeployment(namespace: String, name: String, tagName: String, imageStream: ImageStream): RedeployResult {
        val openShiftResponses = mutableListOf<OpenShiftResponse>()

        val dockerImageUrl = imageStream.metadata.name
        val imageStreamImportResponse = performImageStreamImport(namespace, dockerImageUrl, name)
                .also { openShiftResponses.add(it) }
        if (!imageStreamImportResponse.success) {
            return createFailedRedeployResult(imageStreamImportResponse.exception, openShiftResponses)
        }

        val imageStreamImport = imageStreamImportFromJson(imageStreamImportResponse.responseBody)
        imageStreamImport.findErrorMessage(tagName)
                ?.let { return createFailedRedeployResult(it, openShiftResponses) }


        if (imageStreamImport.isSameImage(imageStream.findCurrentImageHash())) {
            performDeploymentRequest(namespace, name).also { openShiftResponses.add(it) }
            return RedeployResult.fromOpenShiftResponses(openShiftResponses)
        }

        return RedeployResult.fromOpenShiftResponses(openShiftResponses)
    }

    private fun createFailedRedeployResult(message: String?, openShiftResponses: List<OpenShiftResponse>) =
            RedeployResult(success = false, message = message, openShiftResponses = openShiftResponses.toList())

    private fun performImageStreamImport(namespace: String, dockerImageUrl: String, imageStreamName: String): OpenShiftResponse {
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