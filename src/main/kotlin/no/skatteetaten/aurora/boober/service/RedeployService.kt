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

    fun triggerRedeploy(deploymentSpec: AuroraDeploymentSpec, openShiftResponses: List<OpenShiftResponse>): List<OpenShiftResponse> {

        val namespace = deploymentSpec.namespace

        val redeployResourceFromSpec = generateRedeployResourceFromSpec(deploymentSpec, openShiftResponses) ?: return emptyList()
        val command = openShiftClient.createOpenShiftCommand(namespace, redeployResourceFromSpec)

        try {
            val response = openShiftClient.performOpenShiftCommand(namespace, command)
            if (response.command.payload.openshiftKind != "imagestreamimport" || didImportImage(response, openShiftResponses)) {
                return listOf(response)
            }
            val cmd = openShiftClient.createOpenShiftCommand(namespace,
                    openShiftObjectGenerator.generateDeploymentRequest(deploymentSpec.name))
            try {
                return listOf(response, openShiftClient.performOpenShiftCommand(namespace, cmd))
            } catch (e: OpenShiftException) {
                return listOf(response, OpenShiftResponse.fromOpenShiftException(e, command))
            }
        } catch (e: OpenShiftException) {
            return listOf(OpenShiftResponse.fromOpenShiftException(e, command))
        }
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