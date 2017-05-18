package no.skatteetaten.aurora.boober.service.openshift

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import no.skatteetaten.aurora.boober.utils.updateField
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Service

enum class OperationType { CREATED, UPDATE, NONE }

data class OpenShiftResponse(
        val kind: String,
        val operationType: OperationType,
        val previous: JsonNode? = null,
        val payload: JsonNode? = null,
        val responseBody: JsonNode?
) {
    val changed: Boolean
        get() = operationType == OperationType.UPDATE && previous?.at("/metadata/resourceVersion") != responseBody?.at("/metadata/resourceVersion")
}

@Service
class OpenShiftClient(
        @Value("\${openshift.url}") val baseUrl: String,
        val resource: OpenShiftResourceClient,
        val mapper: ObjectMapper
) {

    val logger: Logger = LoggerFactory.getLogger(OpenShiftClient::class.java)

    fun applyMany(namespace: String, openShiftObjects: List<JsonNode>): List<OpenShiftResponse> {

        return openShiftObjects.map { apply(namespace, it) }
    }

    fun apply(namespace: String, json: JsonNode): OpenShiftResponse {

        val kind = json.get("kind")?.asText()?.toLowerCase() ?: throw IllegalArgumentException("Kind must be set")
        val name = json.get("metadata")?.get("name")?.asText() ?: throw IllegalArgumentException("name not specified for resource")

        val existingResource = resource.get(kind, name, namespace)

        if (existingResource == null) {
            val createdResource = resource.post(kind, name, namespace, json)
            return OpenShiftResponse(kind, OperationType.CREATED, null, json, createdResource.body)
        }

        val existing = existingResource.body
        if (kind == "projectrequest") {
            return OpenShiftResponse(kind, OperationType.NONE, existing, json, null)
        }

        json.updateField(existing, "/metadata", "resourceVersion")

        if (kind == "service") {
            json.updateField(existing, "/spec", "clusterIP")
        }

        if (kind == "deploymentconfig") {
            json.updateField(existing, "/spec/triggers/0/imageChangeParams", "lastTriggeredImage")
            json.updateField(existing, "/spec/template/spec/containers/0", "image")
            //TODO:Handle sprocket done?
        }

        if (kind == "buildconfig") {
            json.updateField(existing, "/spec", "triggers")
        }
        val updated = resource.put(kind, name, namespace, json)
        return OpenShiftResponse(kind, OperationType.UPDATE, existing, json, updated.body)

    }

    fun findCurrentUser(token: String): OpenShiftResponse {

        val url = "$baseUrl/oapi/v1/users/~"
        val headers: HttpHeaders = resource.createHeaders(token)

        val currentUser = resource.getExistingResource(headers, url)
        return OpenShiftResponse("user", operationType = OperationType.NONE, responseBody = currentUser?.body)
    }

    fun isValidUser(user: String): Boolean {

        val url = "$baseUrl/oapi/v1/users/$user"
        val headers: HttpHeaders = resource.getAuthorizationHeaders()

        val existingResource = resource.getExistingResource(headers, url)
        return existingResource != null

    }

    fun isValidGroup(group: String): Boolean {

        val url = "$baseUrl/oapi/v1/groups/$group"
        val headers: HttpHeaders = resource.getAuthorizationHeaders()

        val existingResource = resource.getExistingResource(headers, url)
        return existingResource != null
    }
}
