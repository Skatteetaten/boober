package no.skatteetaten.aurora.boober.utils

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.fabric8.openshift.api.model.ImageStream

fun ImageStream.findImageName(): String = this.spec.tags[0].from.name

fun ImageStream.findTagName(): String = this.spec.tags[0].name

fun ImageStream.isSameImage(imageStream: ImageStream): Boolean =
        this.findCurrentImageHash() == imageStream.findCurrentImageHash()

fun ImageStream.findCurrentImageHash(): String? =
        this.status?.tags?.firstOrNull()?.items?.firstOrNull()?.image


fun ImageStream.findErrorMessage(): String? {
    this.status?.tags?.firstOrNull()?.conditions?.let {
        if (it.size > 0 && it[0].status.toLowerCase() == "false") {
            return it[0].message
        }
    }
    return null
}

fun ImageStream.toJsonNode(): JsonNode = jacksonObjectMapper().valueToTree(this)

fun imageStreamFromJson(jsonNode: JsonNode?): ImageStream =
        jacksonObjectMapper().readValue(jsonNode.toString())
