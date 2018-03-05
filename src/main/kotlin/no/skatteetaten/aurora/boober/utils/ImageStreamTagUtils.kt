package no.skatteetaten.aurora.boober.utils

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.fabric8.openshift.api.model.ImageStreamTag

fun ImageStreamTag.findErrorMessage(): String? {
    this.conditions?.let {
        if (it.size > 0 && it[0].status.toLowerCase() == "failure") {
            return it[0].message
        }

    }
    return null
}

fun ImageStreamTag.toJsonNode(): JsonNode = jacksonObjectMapper().valueToTree(this)

fun imageStreamTagFromJson(jsonNode: JsonNode?): ImageStreamTag {

    if (jsonNode == null) throw IllegalArgumentException("Missing ImageStreamTag response body")

    val image = jsonNode.get("image")
    val imageStreamTag = if (image == null) {
        jsonNode
    } else {
        val imageWithoutDockerMetadata = (image as ObjectNode).without("dockerImageMetadata")
        (jsonNode as ObjectNode).set("image", imageWithoutDockerMetadata)
    }

    return jacksonObjectMapper().readValue(imageStreamTag.toString())
}
