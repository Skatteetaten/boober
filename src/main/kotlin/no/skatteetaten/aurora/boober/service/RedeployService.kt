package no.skatteetaten.aurora.boober.service

import io.fabric8.openshift.api.model.ImageStream
import no.skatteetaten.aurora.boober.service.internal.ImageStreamTagGenerator
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.utils.*
import org.springframework.stereotype.Service

@Service
class RedeployService(val openShiftClient: OpenShiftClient,
                      val openShiftCommandBuilder: OpenShiftCommandBuilder,
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

    fun triggerRedeploy(namespace: String, name: String, imageStream: ImageStream?): RedeployResult {
        return if (imageStream == null) {
            requestDeployment(namespace, name)
        } else {
            rolloutDeployment(namespace, name, imageStream)
        }
    }

    fun requestDeployment(namespace: String, name: String): RedeployResult {
        val deploymentRequestResponse = performDeploymentRequest(namespace, name)
        return RedeployResult.fromOpenShiftResponses(listOf(deploymentRequestResponse))
    }

    fun rolloutDeployment(namespace: String, name: String, imageStream: ImageStream): RedeployResult {
        val openShiftResponses = mutableListOf<OpenShiftResponse>()

        val imageStreamTagResponse = performImageStreamTag(namespace, imageStream.findImageName(), imageStream.findTagName())
                .also { openShiftResponses.add(it) }
        if (!imageStreamTagResponse.success) {
            return createFailedRedeployResult(imageStreamTagResponse.exception, openShiftResponses)
        }

        imageStreamTagFromJson(imageStreamTagResponse.responseBody).findErrorMessage()
                ?.let { return createFailedRedeployResult(it, openShiftResponses) }

        val updatedImageStreamResponse = openShiftClient.getImageStream(namespace, name)
                .also { openShiftResponses.add(it) }
        if (!updatedImageStreamResponse.success) {
            return createFailedRedeployResult(updatedImageStreamResponse.exception, openShiftResponses)
        }
        val updatedImageStream = updatedImageStreamResponse.responseBody
                ?.let { imageStreamFromJson(it) }
                ?: return createFailedRedeployResult("Missing information in deployment spec", openShiftResponses)

        updatedImageStream.findErrorMessage()
                ?.let { return createFailedRedeployResult(it, openShiftResponses) }

        if (updatedImageStream.isSameImage(imageStream)) {
            performDeploymentRequest(namespace, name).also { openShiftResponses.add(it) }
            return RedeployResult.fromOpenShiftResponses(openShiftResponses)
        }

        return RedeployResult.fromOpenShiftResponses(openShiftResponses)
    }

    private fun createFailedRedeployResult(message: String?, openShiftResponses: List<OpenShiftResponse>) =
            RedeployResult(success = false, message = message, openShiftResponses = openShiftResponses.toList())

    private fun performImageStreamTag(namespace: String, imageName: String, tagName: String): OpenShiftResponse {
        val imageStreamTag = ImageStreamTagGenerator().create(imageName, tagName)
        val command = openShiftCommandBuilder.createOpenShiftCommand(namespace, imageStreamTag.toJsonNode())
        return openShiftClient.performOpenShiftCommand(namespace, command)
    }

    private fun performDeploymentRequest(namespace: String, name: String): OpenShiftResponse {
        val deploymentRequest = openShiftObjectGenerator.generateDeploymentRequest(name)
        val command = openShiftCommandBuilder.createOpenShiftCommand(name, deploymentRequest)
        return openShiftClient.performOpenShiftCommand(namespace, command)
    }
}