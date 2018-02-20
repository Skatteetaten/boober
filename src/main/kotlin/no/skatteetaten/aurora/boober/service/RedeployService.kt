package no.skatteetaten.aurora.boober.service

import io.fabric8.openshift.api.model.DeploymentConfig
import io.fabric8.openshift.api.model.ImageStream
import io.fabric8.openshift.api.model.ImageStreamTag
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.service.internal.ImageStreamTagGenerator
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.utils.*
import org.springframework.stereotype.Service

@Service
class RedeployService(val openShiftClient: OpenShiftClient, val openShiftObjectGenerator: OpenShiftObjectGenerator) {

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

    fun triggerRedeploy(deploymentSpec: AuroraDeploymentSpec, imageStream: ImageStream?, deploymentConfig: DeploymentConfig?): RedeployResult {
        val type = deploymentSpec.type
        if (type == TemplateType.build || type == TemplateType.development) {
            return RedeployResult(message = "No deploy was made with $deploymentSpec.type type")
        }

        return if (imageStream == null && deploymentConfig != null) {
            runDeploymentRequestProcess(deploymentSpec)
        } else if (imageStream != null && deploymentConfig != null) {
            runImageStreamProcess(deploymentSpec, imageStream, deploymentConfig)
        } else {
            RedeployResult() // TODO error message here? Should it return failed status?
        }
    }

    fun runDeploymentRequestProcess(deploymentSpec: AuroraDeploymentSpec) =
            RedeployResult.fromOpenShiftResponses(listOf(performDeploymentRequest(deploymentSpec)))

    // TODO find a better name for this method?
    fun runImageStreamProcess(deploymentSpec: AuroraDeploymentSpec, imageStream: ImageStream, deploymentConfig: DeploymentConfig): RedeployResult {
        val imageStreamTagResponse = performImageStreamTag(deploymentSpec.environment.namespace, imageStream)
                ?: return RedeployResult() // TODO return failed?
        if (!imageStreamTagResponse.success) {
            return createFailedRedeployResult(imageStreamTagResponse.exception, imageStreamTagResponse)
        }

        ImageStreamTag().from(imageStreamTagResponse).findErrorMessage()?.let {
            return createFailedRedeployResult(it, imageStreamTagResponse)
        }

        val updatedImageStreamResponse = getUpdatedImageStream(deploymentSpec)
                ?: return RedeployResult() // TODO return failed?
        if (!updatedImageStreamResponse.success) {
            return createFailedRedeployResult(updatedImageStreamResponse.exception, imageStreamTagResponse, updatedImageStreamResponse)
        }

        val updatedImageStream = ImageStream().from(updatedImageStreamResponse)
        updatedImageStream.findErrorMessage()?.let {
            return createFailedRedeployResult(it, imageStreamTagResponse, updatedImageStreamResponse)
        }

        if (updatedImageStream.isSameImage(imageStream)) {
            val deploymentRequestResponse = performDeploymentRequest(deploymentSpec)
            return RedeployResult.fromOpenShiftResponses(listOf(imageStreamTagResponse, updatedImageStreamResponse, deploymentRequestResponse))
        }

        return RedeployResult.fromOpenShiftResponses(listOf(imageStreamTagResponse, updatedImageStreamResponse))
    }

    private fun createFailedRedeployResult(message: String?, vararg openShiftResponses: OpenShiftResponse) =
            RedeployResult(success = false, message = message, openShiftResponses = openShiftResponses.toList())

    private fun performImageStreamTag(namespace: String, imageStream: ImageStream): OpenShiftResponse? {
        val imageName = imageStream.findImageName() ?: return null
        val tagName = imageStream.findTagName() ?: return null
        val imageStreamTag = ImageStreamTagGenerator().create(imageName, tagName)
        val command = openShiftClient.createOpenShiftCommand(namespace, imageStreamTag.toJsonNode())
        return try {
            openShiftClient.performOpenShiftCommand(namespace, command)
        } catch (e: OpenShiftException) {
            OpenShiftResponse.fromOpenShiftException(e, command)
        }
    }

    private fun getUpdatedImageStream(deploymentSpec: AuroraDeploymentSpec): OpenShiftResponse? {
        val namespace = deploymentSpec.environment.namespace
        val imageStreamResource = openShiftObjectGenerator.generateImageStream(namespace, deploymentSpec) ?: return null
        val command = openShiftClient.createOpenShiftCommand(namespace, imageStreamResource)
        return try {
            openShiftClient.performOpenShiftCommand(namespace, command)
        } catch (e: OpenShiftException) {
            OpenShiftResponse.fromOpenShiftException(e, command)
        }
    }

    private fun performDeploymentRequest(deploymentSpec: AuroraDeploymentSpec): OpenShiftResponse {
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