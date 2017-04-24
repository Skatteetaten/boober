package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.utils.updateField
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.*
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate
import java.net.URI


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
        val userDetailsProvider: UserDetailsProvider,
        val restTemplate: RestTemplate,
        val resource: OpenshiftResourceClient,
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
        val headers: HttpHeaders = createHeaders(token)

        val currentUser = getExistingResource(headers, url)
        return OpenShiftResponse("user", operationType = OperationType.NONE, responseBody = currentUser?.body)
    }

    private fun getExistingResource(headers: HttpHeaders, url: String): ResponseEntity<JsonNode>? {
        return try {
            val requestEntity = RequestEntity<Any>(headers, HttpMethod.GET, URI(url))
            restTemplate.exchange(requestEntity, JsonNode::class.java)
        } catch(e: Exception) {
            if (e is HttpClientErrorException && e.statusCode != HttpStatus.NOT_FOUND) {
                throw OpenShiftException("An unexpected error occurred when getting resource $url", e)
            }
            null
        }
    }

    private fun createHeaders(token: String): HttpHeaders {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        headers.set("Authorization", "Bearer " + token)
        return headers
    }


    fun isValidUser(user: String): Boolean {
        val url = "$baseUrl/oapi/v1/users/$user"
        val headers: HttpHeaders = createHeaders(userDetailsProvider.getAuthenticatedUser().token)

        val existingResource = getExistingResource(headers, url)
        return existingResource != null

    }

    fun isValidGroup(group: String): Boolean {

        val url = "$baseUrl/oapi/v1/groups/$group"

        val headers: HttpHeaders = createHeaders(userDetailsProvider.getAuthenticatedUser().token)

        val existingResource = getExistingResource(headers, url)
        return existingResource != null
    }
}
