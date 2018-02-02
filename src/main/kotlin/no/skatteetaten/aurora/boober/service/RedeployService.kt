package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.service.openshift.*
import org.springframework.stereotype.Service

@Service
class RedeployService(val openShiftClient: OpenShiftClient, val openShiftObjectGenerator: OpenShiftObjectGenerator) {

    data class RedeployResult @JvmOverloads constructor(
            val success: Boolean = true,
            val message: String? = null) {

        companion object {
            fun fromOpenShiftStatus(openShiftStatus: OpenShiftStatus): RedeployResult {
                val success = openShiftStatus.openShiftResponses.all { it.success }
                val message = if (success) "Redeploy succeeded" else "Redeploy failed"
                return RedeployResult(success = success, message = message)
            }
        }
    }

    fun triggerRedeploy(deploymentSpec: AuroraDeploymentSpec, status: OpenShiftStatus): RedeployResult {

        val namespace = deploymentSpec.environment.namespace
        val redeployResourceFromSpec = generateRedeployResourceFromSpec(deploymentSpec, status) ?: return RedeployResult()
        val command = openShiftClient.createOpenShiftCommand(namespace, redeployResourceFromSpec)

        try {
            status.addResponse(openShiftClient.performOpenShiftCommand(namespace, command))
            status.verifyImageStreamImport().takeUnless { it.success }?.let {
                return RedeployResult(success = false, message = it.message)
            }

            if (status.didImportImage()) {
                return RedeployResult()
            }
            val cmd = openShiftClient.createOpenShiftCommand(namespace,
                    openShiftObjectGenerator.generateDeploymentRequest(deploymentSpec.name))

            try {
                status.addResponse(openShiftClient.performOpenShiftCommand(namespace, cmd))
                return RedeployResult.fromOpenShiftStatus(status)
              } catch (e: OpenShiftException) {
                status.addResponse(OpenShiftResponse.fromOpenShiftException(e, command))
                return RedeployResult.fromOpenShiftStatus(status)
            }
        } catch (e: OpenShiftException) {
            status.addResponse(OpenShiftResponse.fromOpenShiftException(e, command))
            return RedeployResult.fromOpenShiftStatus(status)
        }
    }

    protected fun generateRedeployResourceFromSpec(deploymentSpec: AuroraDeploymentSpec, status: OpenShiftStatus): JsonNode? {
        return generateRedeployResource(deploymentSpec.type, deploymentSpec.name, status)

    }

    protected fun generateRedeployResource(type: TemplateType, name: String, status: OpenShiftStatus): JsonNode? {
        if (type == TemplateType.build || type == TemplateType.development) {
            return null
        }

        if (!status.hasResponse("imagestream") && status.hasResponse("deploymentconfig")) {
            return openShiftObjectGenerator.generateDeploymentRequest(name)
        }

        val streamInfo = status.findImageStreamInformation()

        return openShiftObjectGenerator.generateImageStreamImport(streamInfo!!.name, streamInfo.dockerImageName)
    }
}