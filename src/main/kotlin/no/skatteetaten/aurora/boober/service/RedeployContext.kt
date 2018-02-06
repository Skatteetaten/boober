package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.utils.openshiftKind
import no.skatteetaten.aurora.boober.utils.openshiftName

open class RedeployContext(private val openShiftResponses: List<OpenShiftResponse>,
                      val deploymentSpec: AuroraDeploymentSpec,
                      private val openShiftObjectGenerator: OpenShiftObjectGenerator) {

    lateinit var redeployResource: JsonNode



    fun generateRedeployResource() {
        if (deploymentSpec.type == TemplateType.build || deploymentSpec.type == TemplateType.development) {
            return
        }

        val imageStream = openShiftResponses.find { it.responseBody?.openshiftKind == "imagestream" }
        val deployment = openShiftResponses.find { it.responseBody?.openshiftKind == "deploymentconfig" }
        if (imageStream == null && deployment != null) {
            redeployResource = openShiftObjectGenerator.generateDeploymentRequest(deploymentSpec.name) // TODO flyttes ut til RedeployService
        }

        findImageInformation(openShiftResponses)?.let { imageInformation ->
            imageStream?.responseBody?.takeIf { it.openshiftName == imageInformation.imageStreamName }?.let {
                val tags = it.at("/spec/tags") as ArrayNode
                tags.find { it["name"].asText() == imageInformation.imageStreamTag }?.let {
                    val dockerImageName = it.at("/from/name").asText()
                    redeployResource = openShiftObjectGenerator.generateImageStreamImport(imageInformation.imageStreamName, dockerImageName) // TODO flyttes ut til RedeployService
                }
            }
        }
    }

    private fun findImageInformation(openShiftResponses: List<OpenShiftResponse>): RedeployService.ImageInformation? {
        val dc = openShiftResponses.find { it.responseBody?.openshiftKind == "deploymentconfig" }?.responseBody
                ?: return null

        val triggers = dc.at("/spec/triggers") as ArrayNode
        return triggers.find { it["type"].asText().toLowerCase() == "imagechange" }?.let {
            val (isName, tag) = it.at("/imageChangeParams/from/name").asText().split(':')
            val lastTriggeredImage = it.at("/imageChangeParams/lastTriggeredImage")?.asText() ?: ""
            RedeployService.ImageInformation(lastTriggeredImage, isName, tag)
        }
    }

    fun verifyResponse(response: OpenShiftResponse): RedeployService.VerificationResult {
        val body = response.responseBody ?: return RedeployService.VerificationResult(success = false, message = "No response found")
        val images = body.at("/status/images") as? ArrayNode

        images?.find { it["status"]["status"].textValue()?.toLowerCase().equals("failure") }?.let {
            return RedeployService.VerificationResult(success = false, message = it["status"]["message"]?.textValue())
        }

        return RedeployService.VerificationResult(success = true)
    }

    fun didNotImportImageStream(imageStreamImportResponse: OpenShiftResponse) =
            imageStreamImportResponse.command.payload.openshiftKind != "imagestreamimport" || didImportImage(imageStreamImportResponse)

    private fun didImportImage(response: OpenShiftResponse): Boolean {
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

}