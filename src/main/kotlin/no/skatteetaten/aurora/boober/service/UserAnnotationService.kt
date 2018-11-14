package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.treeToValue
import com.github.fge.jackson.JacksonUtils
import com.github.fge.jackson.jsonpointer.JsonPointer
import com.github.fge.jsonpatch.AddOperation
import com.github.fge.jsonpatch.JsonPatch
import com.github.fge.jsonpatch.RemoveOperation
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig.ClientType
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig.TokenSource
import no.skatteetaten.aurora.boober.utils.isBase64
import no.skatteetaten.aurora.boober.utils.withBase64Prefix
import no.skatteetaten.aurora.boober.utils.withoutBase64Prefix
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.util.Base64Utils

@Service
class UserAnnotationService(
    private val userDetailsProvider: UserDetailsProvider,
    @ClientType(TokenSource.SERVICE_ACCOUNT) private val serviceAccountClient: OpenShiftResourceClient
) {

    fun getAnnotations(): Map<String, JsonNode> {
        val name = userDetailsProvider.getAuthenticatedUser().username
        val response = serviceAccountClient.get("user", "", name)
        return getResponseAnnotations(response)
    }

    fun updateAnnotations(key: String, entries: JsonNode): Map<String, JsonNode> {
        val patchJson = createAddPatch(key, entries)
        val name = userDetailsProvider.getAuthenticatedUser().username
        val response = serviceAccountClient.patch("user", name, patchJson)
        return getResponseAnnotations(response)
    }

    fun createAddPatch(key: String, entries: JsonNode): JsonNode {
        val jsonEntries = jacksonObjectMapper().writeValueAsString(entries)
        val encodedString = Base64Utils.encodeToString(jsonEntries.toByteArray()).withBase64Prefix()

        val operation = if (hasUserAnnotations()) {
            AddOperation(JsonPointer.of("metadata", "annotations", key), TextNode(encodedString))
        } else {
            AddOperation(
                JsonPointer.of("metadata", "annotations"),
                jacksonObjectMapper().convertValue(mapOf(key to encodedString))
            )
        }
        return JacksonUtils.newMapper().convertValue(JsonPatch(listOf(operation)))
    }

    private fun hasUserAnnotations(): Boolean {
        val name = userDetailsProvider.getAuthenticatedUser().username
        val user = serviceAccountClient.get("user", "", name)
        val annotations = user?.body?.at("/metadata/annotations") ?: return false
        return !annotations.isMissingNode
    }

    fun deleteAnnotations(key: String): Map<String, JsonNode> {
        val patchJson = createRemovePatch(key)
        val name = userDetailsProvider.getAuthenticatedUser().username
        val response = serviceAccountClient.patch("user", name, patchJson)
        return getResponseAnnotations(response)
    }

    fun createRemovePatch(key: String): JsonNode {
        val operation = RemoveOperation(JsonPointer.of("metadata", "annotations", key))
        return JacksonUtils.newMapper().convertValue(JsonPatch(listOf(operation)))
    }

    private fun getResponseAnnotations(response: ResponseEntity<JsonNode>?): Map<String, JsonNode> {
        val annotations = response?.body?.at("/metadata/annotations") ?: NullNode.instance
        val entries = jacksonObjectMapper().treeToValue<Map<String, String>>(annotations)
        return entries.mapValues {
            if (it.value.isBase64()) {
                jacksonObjectMapper().readValue<JsonNode>(String(Base64Utils.decodeFromString(it.value.withoutBase64Prefix())))
            } else {
                TextNode(it.value)
            }
        }
    }
}