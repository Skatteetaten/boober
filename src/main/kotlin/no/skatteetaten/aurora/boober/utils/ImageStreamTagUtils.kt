package no.skatteetaten.aurora.boober.utils

import com.fasterxml.jackson.databind.JsonNode
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

fun ImageStreamTag.from(jsonNode: JsonNode?): ImageStreamTag =
        jacksonObjectMapper().readValue(jsonNode.toString())
