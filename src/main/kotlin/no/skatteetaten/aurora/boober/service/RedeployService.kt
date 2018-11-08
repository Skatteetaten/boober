package no.skatteetaten.aurora.boober.service

import io.fabric8.openshift.api.model.DeploymentConfig
import io.fabric8.openshift.api.model.ImageStream
import io.fabric8.openshift.api.model.ImageStreamImport
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.model.openshift.findErrorMessage
import no.skatteetaten.aurora.boober.model.openshift.isDifferentImage
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import no.skatteetaten.aurora.boober.service.openshift.deploymentConfig
import no.skatteetaten.aurora.boober.service.openshift.imageStream
import no.skatteetaten.aurora.boober.service.openshift.imageStreamImport
import no.skatteetaten.aurora.boober.utils.deploymentConfigFromJson
import no.skatteetaten.aurora.boober.utils.findCurrentImageHash
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

        val isResource = openShiftResponses.imageStream()
        val imageStream = isResource?.responseBody?.let { imageStreamFromJson(it) }

        val isiResource = openShiftResponses.imageStreamImport()?.let { isi ->
            isi.responseBody?.let { imageStreamImportFromJson(it) }
        }

        val dcResource = openShiftResponses.deploymentConfig()
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

        isiResource?.findErrorMessage(imageChangeTriggerTagName)?.let {
            return RedeployResult(success = false, message = "ImageStreamImport failed with message=$it")
        }
        return triggerRedeploy(
            imageStream,
            deploymentConfig.metadata.name,
            wasPaused,
            isiResource
        )
    }

    fun triggerRedeploy(deploymentConfig: DeploymentConfig): RedeployResult {
        val namespace = deploymentConfig.metadata.namespace
        val name = deploymentConfig.metadata.name
        val deploymentRequestResponse = performDeploymentRequest(namespace, name)
        return RedeployResult.fromOpenShiftResponses(listOf(deploymentRequestResponse))
    }

    fun triggerRedeploy(
        imageStream: ImageStream,
        dcName: String,
        wasPaused: Boolean,
        imageStreamImport: ImageStreamImport?
    ): RedeployResult {
        val namespace = imageStream.metadata.namespace

        imageStreamImport?.let {

            if (it.isDifferentImage(imageStream.findCurrentImageHash())) {
                return RedeployResult(message = "Image is different so no explicit deploy")
            }
        }

        // TODO: not 100% sure if this is correct or not.
        if (wasPaused) {
            return RedeployResult(message = "Deploy was paused so no explicit deploy")
        }
        val deploymentRequestResponse = performDeploymentRequest(namespace, dcName)
        return RedeployResult.fromOpenShiftResponses(listOf(deploymentRequestResponse))
    }

    private fun performDeploymentRequest(namespace: String, name: String): OpenShiftResponse {
        val deploymentRequest = openShiftObjectGenerator.generateDeploymentRequest(name)
        val command = OpenshiftCommand(OperationType.CREATE, deploymentRequest)
        return openShiftClient.performOpenShiftCommand(namespace, command)
    }
}