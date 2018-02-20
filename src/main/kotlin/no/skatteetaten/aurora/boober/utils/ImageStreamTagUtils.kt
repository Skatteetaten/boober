package no.skatteetaten.aurora.boober.utils

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.fabric8.openshift.api.model.ImageStreamTag
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse

fun ImageStreamTag.findErrorMessage(): String? {
    this.conditions?.let {
        if (it.size > 0 && it[0].status.toLowerCase() == "failure") {
            return it[0].message
        }

    }
    return null
}

fun ImageStreamTag.toJsonNode(): JsonNode = jacksonObjectMapper().valueToTree(this)

fun ImageStreamTag.from(openShiftResponse: OpenShiftResponse): ImageStreamTag =
        jacksonObjectMapper().readValue(openShiftResponse.responseBody.toString())
