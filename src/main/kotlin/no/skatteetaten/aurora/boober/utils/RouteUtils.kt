package no.skatteetaten.aurora.boober.utils

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.fabric8.openshift.api.model.Route

fun routeFromJson(jsonNode: JsonNode): Route = jacksonObjectMapper().convertValue(jsonNode)

fun Route.findErrorMessage(): String? {
    val ingress = this.status.ingress
    if (this.status.ingress.isNullOrEmpty()) {
        return null
    }

    val result = ingress.flatMap { it.conditions }
        .filter { it.status == "False" }
        .map { it.message }

    if (result.isEmpty()) {
        return null
    }
    return result.joinToString(",")
}
