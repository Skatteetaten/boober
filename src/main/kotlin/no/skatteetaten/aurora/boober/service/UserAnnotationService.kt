package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import org.springframework.stereotype.Service

@Service
class UserAnnotationService(private val openShiftClient: OpenShiftClient) {

    fun addAnnotation(user: String, key: String, entries: Map<String, Any>): OpenShiftResponse {
        val patchJsonNode = jacksonObjectMapper().readValue<JsonNode>(createJsonPatch(key, entries))
        return openShiftClient.performOpenShiftCommand("", OpenshiftCommand(OperationType.PATCH, patchJsonNode))
    }

    fun createJsonPatch(key: String, entries: Map<String, Any>): String {
        val jsonEntries = jacksonObjectMapper().writeValueAsString(entries)
        return """[{
                "op":"add",
                "path":"/metadata/annotations/$key",
                "value":$jsonEntries
                }]""".trimIndent()
    }
}