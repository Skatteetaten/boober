package no.skatteetaten.aurora.boober.service

import io.fabric8.openshift.api.model.DeploymentConfig
import io.fabric8.openshift.api.model.ImageStream
import no.skatteetaten.aurora.boober.service.internal.ImageStreamImportGenerator
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import no.skatteetaten.aurora.boober.utils.findCurrentImageHash
import no.skatteetaten.aurora.boober.utils.findDockerImageUrl
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
        val imageChangeTriggerTagName = deploymentConfig.findImageChangeTriggerTagName()
        return if (imageStream == null || imageChangeTriggerTagName == null) {
            redeploy(deploymentConfig)
        } else {
            redeploy(imageStream, imageChangeTriggerTagName)
        }
    }

    fun redeploy(deploymentConfig: DeploymentConfig): RedeployResult {
        val namespace = deploymentConfig.metadata.namespace
        val name = deploymentConfig.metadata.name
        val deploymentRequestResponse = performDeploymentRequest(namespace, name)
        return RedeployResult.fromOpenShiftResponses(listOf(deploymentRequestResponse))
    }

    fun redeploy(imageStream: ImageStream, tagName: String): RedeployResult {
        val openShiftResponses = mutableListOf<OpenShiftResponse>()

        val namespace = imageStream.metadata.namespace
        val name = imageStream.metadata.name
        val dockerImageUrl = imageStream.findDockerImageUrl(tagName) ?: throw IllegalArgumentException("Missing docker image url")
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