package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.node.ArrayNode
import io.fabric8.openshift.api.model.DeploymentConfig
import io.fabric8.openshift.api.model.ImageStream
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse

open class RedeployContext(private val imageStream: ImageStream?, private val deploymentConfig: DeploymentConfig?) {

    data class VerificationResult(val success: Boolean = true, val message: String? = null)

    data class ImageInformation(val lastTriggeredImage: String, val imageStreamName: String, val imageStreamTag: String)

    open fun isDeploymentRequest(): Boolean = imageStream == null && deploymentConfig != null

    open fun verifyResponse(response: OpenShiftResponse): VerificationResult {
        val body = response.responseBody
                ?: return VerificationResult(success = false, message = "No response found")
        val images = body.at("/status/images") as? ArrayNode

        images?.find { it["status"]["status"].textValue()?.toLowerCase().equals("failure") }?.let {
            return VerificationResult(success = false, message = it["status"]["message"]?.textValue())
        }

        return VerificationResult(success = true)
    }

    open fun didImportImage(response: OpenShiftResponse): Boolean {
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

    open fun findImageInformation(): ImageInformation? {
        deploymentConfig?.spec?.triggers?.get(0)?.imageChangeParams?.let {
            val name = it.from?.name
            if (name != null) {
                val (isName, tag) = name.split(':')
                val lastTriggeredImage = it.lastTriggeredImage ?: ""
                return ImageInformation(lastTriggeredImage, isName, tag)
            }
        }

        return null
    }

    open fun findImageName(): String? {
        return imageStream?.spec?.tags?.get(0)?.from?.name
    }

}