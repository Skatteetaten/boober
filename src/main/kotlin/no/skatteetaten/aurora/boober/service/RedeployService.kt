package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.utils.openshiftKind
import no.skatteetaten.aurora.boober.utils.openshiftName
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

    fun triggerRedeploy(deploymentSpec: AuroraDeploymentSpec, openShiftResponses: List<OpenShiftResponse>): RedeployResult {

        val namespace = deploymentSpec.environment.namespace

        val redeployResourceFromSpec = generateRedeployResourceFromSpec(deploymentSpec, openShiftResponses) ?: return RedeployResult()
        val command = openShiftClient.createOpenShiftCommand(namespace, redeployResourceFromSpec)

        try {
            val response = openShiftClient.performOpenShiftCommand(namespace, command)

            verifyResponse(response).takeUnless { it.success }?.let {
                return RedeployResult(success = false, message = it?.message, openShiftResponses = listOf(response))
            }

            if (response.command.payload.openshiftKind != "imagestreamimport" || didImportImage(response, openShiftResponses)) {
                return RedeployResult.fromOpenShiftResponses(listOf(response))
            }
            val cmd = openShiftClient.createOpenShiftCommand(namespace,
                    openShiftObjectGenerator.generateDeploymentRequest(deploymentSpec.name))

            try {
                return RedeployResult.fromOpenShiftResponses(listOf(response, openShiftClient.performOpenShiftCommand(namespace, cmd)))
            } catch (e: OpenShiftException) {
                return RedeployResult.fromOpenShiftResponses(listOf(response, OpenShiftResponse.fromOpenShiftException(e, command)))
            }
        } catch (e: OpenShiftException) {
            return RedeployResult.fromOpenShiftResponses(listOf(OpenShiftResponse.fromOpenShiftException(e, command)))
        }
    }

    protected fun verifyResponse(response: OpenShiftResponse): VerificationResult {
        val body = response.responseBody ?: return VerificationResult(success = false, message = "No response found")
        val images = body.at("/status/images") as? ArrayNode

        images?.find { it["status"]["status"].textValue()?.toLowerCase().equals("failure") }?.let {
            return VerificationResult(success = false, message = it["status"]["message"]?.textValue())
        }

        return VerificationResult(success = true)
    }

    protected fun didImportImage(response: OpenShiftResponse, openShiftResponses: List<OpenShiftResponse>): Boolean {

        val body = response.responseBody ?: return true
        val info = findImageInformation(openShiftResponses) ?: return true
        if (info.lastTriggeredImage.isBlank()) {
            return false
        }

        val tags = body.at("/status/import/status/tags") as ArrayNode
        tags.find { it["tag"].asText() == info.imageStreamTag }?.let {
            val allTags = it["items"] as ArrayNode
            val tag = allTags.first()
            return tag["dockerImageReference"].asText() != info.lastTriggeredImage
        }

        return true
    }

    protected fun findImageInformation(openShiftResponses: List<OpenShiftResponse>): ImageInformation? {
        val dc = openShiftResponses.find { it.responseBody?.openshiftKind == "deploymentconfig" }?.responseBody ?: return null

        val triggers = dc.at("/spec/triggers") as ArrayNode
        return triggers.find { it["type"].asText().toLowerCase() == "imagechange" }?.let {
            val (isName, tag) = it.at("/imageChangeParams/from/name").asText().split(':')
            val lastTriggeredImage = it.at("/imageChangeParams/lastTriggeredImage")?.asText() ?: ""
            ImageInformation(lastTriggeredImage, isName, tag)
        }
    }

    protected fun generateRedeployResourceFromSpec(deploymentSpec: AuroraDeploymentSpec, openShiftResponses: List<OpenShiftResponse>): JsonNode? {
        return generateRedeployResource(deploymentSpec.type, deploymentSpec.name, openShiftResponses)

    }

    protected fun generateRedeployResource(type: TemplateType, name: String, openShiftResponses: List<OpenShiftResponse>): JsonNode? {
        if (type == TemplateType.build || type == TemplateType.development) {
            return null
        }

        val imageStream = openShiftResponses.find { it.responseBody?.openshiftKind == "imagestream" }
        val deployment = openShiftResponses.find { it.responseBody?.openshiftKind == "deploymentconfig" }
        if (imageStream == null && deployment != null) {
            return openShiftObjectGenerator.generateDeploymentRequest(name)
        }

        findImageInformation(openShiftResponses)?.let { imageInformation ->
            imageStream?.responseBody?.takeIf { it.openshiftName == imageInformation.imageStreamName }?.let {
                val tags = it.at("/spec/tags") as ArrayNode
                tags.find { it["name"].asText() == imageInformation.imageStreamTag }?.let {
                    val dockerImageName = it.at("/from/name").asText()
                    return openShiftObjectGenerator.generateImageStreamImport(imageInformation.imageStreamName, dockerImageName)
                }
            }
        }

        return null
    }
}