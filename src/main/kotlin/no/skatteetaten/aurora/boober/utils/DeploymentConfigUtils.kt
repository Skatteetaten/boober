package no.skatteetaten.aurora.boober.utils

import com.fasterxml.jackson.databind.node.ArrayNode
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse

data class ImageInformation(val lastTriggeredImage: String, val imageStreamName: String, val imageStreamTag: String)

fun DeploymentConfig.findImageInformation(): ImageInformation? {
    val imageChangeParams = this.spec?.triggers?.get(0)?.imageChangeParams
    val lastTriggeredImage = imageChangeParams?.lastTriggeredImage ?: ""
    return imageChangeParams?.from?.name?.let {
        val (isName, tag) = it.split(':')
        ImageInformation(lastTriggeredImage, isName, tag)
    }
}

fun DeploymentConfig.didImportImage(response: OpenShiftResponse): Boolean {
    val body = response.responseBody ?: return true
    val info = this.findImageInformation() ?: return true
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