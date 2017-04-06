package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.controller.SetupController
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.model.Rolebinding
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
        val operationType: OperationType,
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

        return openShiftObjects.map {
            Thread.sleep(1000)
            //race condition if we create resources to fast
            apply(namespace, it, dryRun)
        }
    }

    fun apply(namespace: String, json: JsonNode, dryRun: Boolean = false): OpenShiftResponse {

        val urls: OpenShiftApiUrls = OpenShiftApiUrls.createUrlsForResource(baseUrl, namespace, json)
        val headers: HttpHeaders = createHeaders(userDetailsProvider.getAuthenticatedUser().token)

        val existingResource: ResponseEntity<JsonNode>? = getExistingResource(headers, urls.get)
        return if (existingResource != null) {
            logger.info("Resource ${urls.get} already exists. Skipping...")
            OpenShiftResponse(OperationType.NONE, json, existingResource.body)
        } else {
            val createdResource = if (!dryRun) createResource(headers, urls.update, json) else null
            OpenShiftResponse(OperationType.CREATED, json, createdResource?.body)
        }
    }

    fun findCurrentUser(token: String): OpenShiftResponse {

        val url = OpenShiftApiUrls.getCurrentUserPath(baseUrl)
        val headers: HttpHeaders = createHeaders(token)

        val currentUser = getExistingResource(headers, url)
        return OpenShiftResponse(operationType = OperationType.NONE, responseBody = currentUser?.body)
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

    fun updateRoleBinding(namespace: String, role: String, users: Set<String>, groups: Set<String>): OpenShiftResponse? {
        val url: OpenShiftApiUrls = OpenShiftApiUrls.createOpenShiftApiUrls(baseUrl, "rolebinding", namespace, role)
        val authenticatedUser = userDetailsProvider.getAuthenticatedUser()

        val headers: HttpHeaders = createHeaders(authenticatedUser.token)

        val response = getExistingResource(headers, url.get)

        val mapper = jacksonObjectMapper()

        val newBindings: JsonNode = mapper.valueToTree(Rolebinding(groups, users))

        if (response?.body == null) {
            return null
        }
        val roleBinding: ObjectNode = response.body as ObjectNode
        roleBinding.set("groupNames", newBindings.get("groupNames"))
        roleBinding.set("userNames", newBindings.get("userNames"))
        roleBinding.set("subjects", newBindings.get("subjects"))
        val entity = HttpEntity<JsonNode>(roleBinding, headers)

        val createResponse: ResponseEntity<JsonNode> = try {
            restTemplate.exchange(url.get, HttpMethod.PUT, entity, JsonNode::class.java)
        } catch(e: HttpClientErrorException) {
            throw OpenShiftException("Error saving url=${url.get}, with message=${e.message}", e)
        }
        logger.debug("Body=${createResponse.body}")

        return OpenShiftResponse(OperationType.UPDATE, roleBinding, createResponse.body)

    }

    fun isValidUser(user: String): Boolean {
        return true
    }

    fun isValidGroup(group: String): Boolean {
        return true
    }
}
