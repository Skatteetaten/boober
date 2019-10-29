package no.skatteetaten.aurora.boober.facade

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.MissingNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.treeToValue
import com.fkorotkov.kubernetes.newObjectMeta
import io.fabric8.kubernetes.api.model.ObjectMeta
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig.ClientType
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig.TokenSource
import no.skatteetaten.aurora.boober.utils.base64ToJsonNode
import no.skatteetaten.aurora.boober.utils.toBase64
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service

@Service
class UserAnnotationFacade(
    private val userDetailsProvider: UserDetailsProvider,
    @ClientType(TokenSource.SERVICE_ACCOUNT) private val serviceAccountClient: OpenShiftResourceClient
) {

    fun getAnnotations(): Map<String, JsonNode> {
        val name = userDetailsProvider.getAuthenticatedUser().username
        val response = serviceAccountClient.get("user", name = name)
        return getResponseAnnotations(response)
    }

    fun updateAnnotations(key: String, entries: JsonNode): Map<String, JsonNode> {
        val patchJson = createUpdatePatch(key, entries)
        val name = userDetailsProvider.getAuthenticatedUser().username
        val response = serviceAccountClient.strategicMergePatch("user", name, patchJson)
        return getResponseAnnotations(response)
    }

    fun createUpdatePatch(key: String, entries: JsonNode): JsonNode {
        val jsonEntries = jacksonObjectMapper().writeValueAsString(entries)
        return UserAnnotation.create(key, jsonEntries.toBase64()).toJsonNode()
    }

    fun deleteAnnotations(key: String): Map<String, JsonNode> {
        val patchJson = createRemovePatch(key)
        val name = userDetailsProvider.getAuthenticatedUser().username
        val response = serviceAccountClient.strategicMergePatch("user", name, patchJson)
        return getResponseAnnotations(response)
    }

    fun createRemovePatch(key: String): JsonNode = UserAnnotation.emptyAnnotation(
        key
    ).toJsonNode()

    private fun getResponseAnnotations(response: ResponseEntity<JsonNode>?): Map<String, JsonNode> {
        val annotations = response?.body?.at("/metadata/annotations") ?: return emptyMap()
        if (annotations is MissingNode) {
            return emptyMap()
        }

        val entries = jacksonObjectMapper().treeToValue<Map<String, String>>(annotations)
        return entries.mapValues { it.value.base64ToJsonNode() }
    }
}

private data class UserAnnotation(val metadata: ObjectMeta) {
    companion object {
        fun create(key: String, annotation: String?) =
            UserAnnotation(metadata = newObjectMeta {
                annotations = mapOf(key to annotation)
            })

        fun emptyAnnotation(key: String) = UserAnnotation.create(key, null)
    }

    fun toJsonNode(): JsonNode = jacksonObjectMapper().convertValue(this)
}
