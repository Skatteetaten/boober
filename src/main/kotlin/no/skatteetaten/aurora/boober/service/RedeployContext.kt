package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.node.ArrayNode
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.utils.openshiftKind
import no.skatteetaten.aurora.boober.utils.openshiftName

open class RedeployContext(openShiftResponses: List<OpenShiftResponse>) {

    private val imageStream: OpenShiftResponse? = openShiftResponses.find { it.responseBody?.openshiftKind == "imagestream" }
    private val deploymentConfig: OpenShiftResponse? = openShiftResponses.find { it.responseBody?.openshiftKind == "deploymentconfig" }

    open fun isDeploymentRequest(): Boolean = imageStream == null && deploymentConfig != null

    open fun verifyResponse(response: OpenShiftResponse): RedeployService.VerificationResult {
        val body = response.responseBody
                ?: return RedeployService.VerificationResult(success = false, message = "No response found")
        val images = body.at("/status/images") as? ArrayNode

        images?.find { it["status"]["status"].textValue()?.toLowerCase().equals("failure") }?.let {
            return RedeployService.VerificationResult(success = false, message = it["status"]["message"]?.textValue())
        }

        return RedeployService.VerificationResult(success = true)
    }

    open fun noImageStreamImportRequired(imageStreamImportResponse: OpenShiftResponse) =
            imageStreamImportResponse.command.payload.openshiftKind != "imagestreamimport" || didImportImage(imageStreamImportResponse)

    private fun didImportImage(response: OpenShiftResponse): Boolean {
        val body = response.responseBody ?: return true
        val info = findImageInformation() ?: return true
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

    open fun findImageInformation(): RedeployService.ImageInformation? {
        val dc = deploymentConfig?.responseBody ?: return null

        val triggers = dc.at("/spec/triggers") as ArrayNode
        return triggers.find { it["type"].asText().toLowerCase() == "imagechange" }?.let {
            val (isName, tag) = it.at("/imageChangeParams/from/name").asText().split(':')
            val lastTriggeredImage = it.at("/imageChangeParams/lastTriggeredImage")?.asText() ?: ""
            RedeployService.ImageInformation(lastTriggeredImage, isName, tag)
        }
    }

    open fun findImageName(): String? {
        val imageInformation = findImageInformation()
        imageInformation?.let { img ->
            imageStream?.responseBody?.takeIf { it.openshiftName == img.imageStreamName }?.let {
                val tags = it.at("/spec/tags") as ArrayNode
                tags.find { it["name"].asText() == img.imageStreamTag }?.let {
                    return it.at("/from/name").asText()
                }
            }
        }
        return null
    }

}