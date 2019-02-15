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
import no.skatteetaten.aurora.boober.utils.convert
import no.skatteetaten.aurora.boober.utils.findCurrentImageHash
import no.skatteetaten.aurora.boober.utils.findImageChangeTriggerTagName
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
            fun fromOpenShiftResponses(
                openShiftResponses: List<OpenShiftResponse>,
                extraMessage: String = ""
            ): RedeployResult {
                val success = openShiftResponses.all { it.success }
                val message = if (success) "succeeded." else "failed."
                return RedeployResult(
                    openShiftResponses = openShiftResponses,
                    success = success,
                    message = "$extraMessage $message"
                )
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
        val imageStream: ImageStream? = isResource?.responseBody?.let { it.convert() }

        val isiResource: ImageStreamImport? = openShiftResponses.imageStreamImport()?.let { isi ->
            isi.responseBody?.let { it.convert() }
        }

        val dcResource = openShiftResponses.deploymentConfig()
        val oldDcResource: DeploymentConfig? = dcResource?.command?.previous?.let { it.convert() }
        val wasPaused = oldDcResource?.spec?.replicas == 0

        val deploymentConfig = dcResource?.responseBody?.let { it.convert<DeploymentConfig>() }
            ?: throw IllegalArgumentException("Missing DeploymentConfig")

        if (isResource?.command?.operationType == OperationType.CREATE) {
            return RedeployResult(message = "New application version found.")
        }
        val imageChangeTriggerTagName = deploymentConfig.findImageChangeTriggerTagName()
        if (imageStream == null || imageChangeTriggerTagName == null) {
            return triggerRedeploy(deploymentConfig)
        }

        val isNewVersion = isiResource?.let {
            it.isDifferentImage(imageStream.findCurrentImageHash(imageChangeTriggerTagName))
        } ?: false

        val versionMessage = if (isNewVersion) {
            "New application version found."
        } else {
            "No new application version found."
        }

        if (!wasPaused && isNewVersion) {
            return RedeployResult(message = versionMessage)
        }

        val namespace = imageStream.metadata.namespace
        val deploymentRequestResponse = performDeploymentRequest(namespace, deploymentConfig.metadata.name)
        return RedeployResult.fromOpenShiftResponses(
            listOf(deploymentRequestResponse),
            "$versionMessage Config changes deployment"
        )
    }

    fun triggerRedeploy(deploymentConfig: DeploymentConfig): RedeployResult {
        val namespace = deploymentConfig.metadata.namespace
        val name = deploymentConfig.metadata.name
        val deploymentRequestResponse = performDeploymentRequest(namespace, name)
        return RedeployResult.fromOpenShiftResponses(listOf(deploymentRequestResponse), "Deployment")
    }

    fun performDeploymentRequest(namespace: String, name: String): OpenShiftResponse {
        val deploymentRequest = openShiftObjectGenerator.generateDeploymentRequest(name)
        val command = OpenshiftCommand(OperationType.CREATE, deploymentRequest)
        return openShiftClient.performOpenShiftCommand(namespace, command)
    }
}