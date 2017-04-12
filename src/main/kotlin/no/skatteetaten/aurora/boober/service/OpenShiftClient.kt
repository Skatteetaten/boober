package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.controller.SetupController
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
)

@Service
class OpenShiftClient(
        @Value("\${openshift.url}") val baseUrl: String,
        val userDetailsProvider: UserDetailsProvider,
        val restTemplate: RestTemplate
) {

    val logger: Logger = LoggerFactory.getLogger(SetupController::class.java)

    fun applyMany(namespace: String, openShiftObjects: List<JsonNode>, dryRun: Boolean = false): List<OpenShiftResponse> {

        val responses = openShiftObjects.map {
            Thread.sleep(1000)
            //race condition if we create resources to fast
            apply(namespace, it, dryRun)
        }


        //TODO:Do we need a manual deploy?
        //If the build.version is changed we do not need to deploy. Otherwise we do.
        //That is start build for deployment or start deploy for others
        return responses

    }

    fun apply(namespace: String, json: JsonNode, dryRun: Boolean = false): OpenShiftResponse {

        val urls: OpenShiftApiUrls = OpenShiftApiUrls.createUrlsForResource(baseUrl, namespace, json)
        val headers: HttpHeaders = createHeaders(userDetailsProvider.getAuthenticatedUser().token)
        val kind = json.get("kind")?.asText()?.toLowerCase() ?: throw IllegalArgumentException("Kind must be set")

        val existingResource: ResponseEntity<JsonNode>? = getExistingResource(headers, urls.get)
        if (existingResource == null) {
            val createdResource = if (!dryRun) createResource(headers, urls.create, json) else null
            return OpenShiftResponse(kind, OperationType.CREATED, null, json, createdResource?.body)
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
        val updated = if (!dryRun) updateResource(headers, urls.update, json) else null
        return OpenShiftResponse(kind, OperationType.UPDATE, existing, json, updated?.body)


    }



    private fun updateResource(headers: HttpHeaders, updateUrl: String, payload: JsonNode): ResponseEntity<JsonNode> {

        logger.info("Update resource at $updateUrl")

        val entity = HttpEntity<JsonNode>(payload, headers)

        val updateResponse: ResponseEntity<JsonNode> = try {
            restTemplate.exchange(updateUrl, HttpMethod.PUT, entity, JsonNode::class.java)
        } catch(e: HttpClientErrorException) {
            throw OpenShiftException("Error saving url=${updateUrl}, with message=${e.message}", e)
        }
        return updateResponse
    }

    fun findCurrentUser(token: String): OpenShiftResponse {

        val url = OpenShiftApiUrls.getCurrentUserPath(baseUrl)
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

    private fun createResource(headers: HttpHeaders, updateUrl: String, payload: JsonNode): ResponseEntity<JsonNode> {

        logger.info("Creating resource at ${updateUrl}")

        val entity = HttpEntity<JsonNode>(payload, headers)
        val createResponse: ResponseEntity<JsonNode> = try {
            restTemplate.postForEntity(updateUrl, entity, JsonNode::class.java)
        } catch(e: HttpClientErrorException) {
            throw OpenShiftException("Error saving url=$updateUrl, with message=${e.message}", e)
        }
        logger.debug("Body=${createResponse.body}")
        return createResponse
    }

    private fun createHeaders(token: String): HttpHeaders {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        headers.set("Authorization", "Bearer " + token)
        return headers
    }

    fun isValidUser(user: String): Boolean {
        val url = OpenShiftApiUrls.createOpenShiftApiUrls(baseUrl, "user", user)
        val headers: HttpHeaders = createHeaders(userDetailsProvider.getAuthenticatedUser().token)

        val existingResource = getExistingResource(headers, url.get)
        return existingResource != null

    }

    fun isValidGroup(group: String): Boolean {
        val url = OpenShiftApiUrls.createOpenShiftApiUrls(baseUrl, "group", group)
        val headers: HttpHeaders = createHeaders(userDetailsProvider.getAuthenticatedUser().token)

        val existingResource = getExistingResource(headers, url.get)
        return existingResource != null
    }
}
