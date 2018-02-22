package no.skatteetaten.aurora.boober.service

import io.fabric8.openshift.api.model.DeploymentConfig
import io.fabric8.openshift.api.model.ImageStream
import io.fabric8.openshift.api.model.ImageStreamTag
import no.skatteetaten.aurora.boober.service.internal.ImageStreamTagGenerator
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.utils.*
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
        return if (imageStream == null) {
            requestDeployment(deploymentConfig)
        } else {
            rolloutDeployment(imageStream, deploymentConfig)
        }
    }

    fun requestDeployment(deploymentConfig: DeploymentConfig): RedeployResult {
        val namespace = deploymentConfig.metadata.namespace
        val name = deploymentConfig.metadata.name
        val deploymentRequestResponse = performDeploymentRequest(namespace, name)
        return RedeployResult.fromOpenShiftResponses(listOf(deploymentRequestResponse))
    }

    fun rolloutDeployment(imageStream: ImageStream, deploymentConfig: DeploymentConfig): RedeployResult {
        val namespace = deploymentConfig.metadata.namespace
        val name = deploymentConfig.metadata.name

        val imageStreamTagResponse = performImageStreamTag(namespace, imageStream.findImageName(), imageStream.findTagName())
        if (!imageStreamTagResponse.success) {
            return createFailedRedeployResult(imageStreamTagResponse.exception, imageStreamTagResponse)
        }

        ImageStreamTag().from(imageStreamTagResponse.responseBody).findErrorMessage()?.let {
            return createFailedRedeployResult(it, imageStreamTagResponse)
        }

        val updatedImageStream = getUpdatedImageStream(namespace, name)
                ?: return createFailedRedeployResult("Missing information in deployment spec", imageStreamTagResponse)
        updatedImageStream.findErrorMessage()?.let {
            return createFailedRedeployResult(it, imageStreamTagResponse)
        }

        if (updatedImageStream.isSameImage(imageStream)) {
            val deploymentRequestResponse = performDeploymentRequest(namespace, name)
            return RedeployResult.fromOpenShiftResponses(listOf(imageStreamTagResponse, deploymentRequestResponse))
        }

        return RedeployResult.fromOpenShiftResponses(listOf(imageStreamTagResponse))
    }

    private fun createFailedRedeployResult(message: String?, vararg openShiftResponses: OpenShiftResponse) =
            RedeployResult(success = false, message = message, openShiftResponses = openShiftResponses.toList())

    private fun performImageStreamTag(namespace: String, imageName: String, tagName: String): OpenShiftResponse {
        val imageStreamTag = ImageStreamTagGenerator().create(imageName, tagName)
        val command = openShiftClient.createOpenShiftCommand(namespace, imageStreamTag.toJsonNode())
        return try {
            openShiftClient.performOpenShiftCommand(namespace, command)
        } catch (e: OpenShiftException) {
            OpenShiftResponse.fromOpenShiftException(e, command)
        }
    }

    private fun getUpdatedImageStream(namespace: String, name: String): ImageStream? {
        val imageStream = openShiftClient.getImageStream(namespace, name) ?: return null
        return ImageStream().from(imageStream)
    }

    private fun performDeploymentRequest(namespace: String, name: String): OpenShiftResponse {
        val deploymentRequest = openShiftObjectGenerator.generateDeploymentRequest(name)
        val command = openShiftClient.createOpenShiftCommand(namespace, deploymentRequest)
        return try {
            openShiftClient.performOpenShiftCommand(namespace, command)
        } catch (e: OpenShiftException) {
            OpenShiftResponse.fromOpenShiftException(e, command)
        }
    }
}