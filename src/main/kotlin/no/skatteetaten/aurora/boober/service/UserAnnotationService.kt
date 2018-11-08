package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.fge.jackson.JacksonUtils
import com.github.fge.jackson.jsonpointer.JsonPointer
import com.github.fge.jsonpatch.AddOperation
import com.github.fge.jsonpatch.JsonPatch
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig.ClientType
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig.TokenSource
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import org.springframework.stereotype.Service

@Service
class UserAnnotationService(
    private val userDetailsProvider: UserDetailsProvider,
    @ClientType(TokenSource.SERVICE_ACCOUNT) private val serviceAccountClient: OpenShiftResourceClient
) {

    fun addAnnotation(key: String, entries: Map<String, Any>): OpenShiftResponse {
        val patchJson = createAddPatch(key, entries)
        val cmd = OpenshiftCommand(OperationType.UPDATE, patchJson)
        return try {
            val name = userDetailsProvider.getAuthenticatedUser().username
            val response = serviceAccountClient.patch("user", name, patchJson)
            OpenShiftResponse(cmd, response.body)
        } catch (e: OpenShiftException) {
            OpenShiftResponse.fromOpenShiftException(e, cmd)
        }
    }

    fun createAddPatch(key: String, entries: Map<String, Any>): JsonNode {
        val jsonEntries = jacksonObjectMapper().convertValue<JsonNode>(entries)
        val patch = JsonPatch(listOf(AddOperation(JsonPointer.of("metadata", "annotations", key), jsonEntries)))
        return JacksonUtils.newMapper().convertValue(patch)
    }
}