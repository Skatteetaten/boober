package no.skatteetaten.aurora.boober.service

import io.fabric8.openshift.api.model.DeploymentConfig
import io.fabric8.openshift.api.model.ImageStream
import io.fabric8.openshift.api.model.ImageStreamImport
import no.skatteetaten.aurora.boober.model.TemplateType
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
            fun fromOpenShiftResponses(openShiftResponses: List<OpenShiftResponse>, extraMessage: String = ""): RedeployResult {
                val success = openShiftResponses.all { it.success }
                val message = if (success) "succeeded." else "failed."
                return RedeployResult(openShiftResponses = openShiftResponses, success = success, message = "$extraMessage $message")
            }
        }
    }

    fun triggerRedeploy(
        openShiftResponses: List<OpenShiftResponse>,
        type: TemplateType
    ): RedeployResult {

        if (type == TemplateType.development) {
            return RedeployResult(message = "No deploy made since type=$type, deploy via oc start-build.")
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
            return RedeployResult(message = "New application version found.")
        }
        val imageChangeTriggerTagName = deploymentConfig.findImageChangeTriggerTagName()
        if (imageStream == null || imageChangeTriggerTagName == null) {
            return triggerRedeploy(deploymentConfig)
        }

        isiResource?.let {
            if (it.isDifferentImage(imageStream.findCurrentImageHash(imageChangeTriggerTagName))) {
                return RedeployResult(message = "New application version found.")
            }
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
        return RedeployResult.fromOpenShiftResponses(listOf(deploymentRequestResponse), "Deployment")
    }

    fun triggerRedeploy(
        imageStream: ImageStream,
        dcName: String,
        wasPaused: Boolean,
        imageStreamImport: ImageStreamImport?
    ): RedeployResult {
        val namespace = imageStream.metadata.namespace

        if (wasPaused) {
            return RedeployResult(message = "Paused deploy resumed.")
        }
        val deploymentRequestResponse = performDeploymentRequest(namespace, dcName)
        return RedeployResult.fromOpenShiftResponses(listOf(deploymentRequestResponse), "No new application version found. Config changes deployment")
    }

    fun performDeploymentRequest(namespace: String, name: String): OpenShiftResponse {
        val deploymentRequest = openShiftObjectGenerator.generateDeploymentRequest(name)
        val command = OpenshiftCommand(OperationType.CREATE, deploymentRequest)
        return openShiftClient.performOpenShiftCommand(namespace, command)
    }
}