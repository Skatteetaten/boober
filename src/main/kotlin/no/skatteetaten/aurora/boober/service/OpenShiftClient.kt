package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.controller.SetupController
import no.skatteetaten.aurora.boober.model.Rolebinding
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.*
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate
import java.net.URI


enum class OperationType {CREATED, UPDATE, NONE }


data class OpenShiftResponse(
        val operationType: OperationType,
        val payload: JsonNode? = null,
        val responseBody: JsonNode?
)

@Service
class OpenShiftClient(
        @Value("\${openshift.url}") val baseUrl: String,
        val restTemplate: RestTemplate
) {

    val logger: Logger = LoggerFactory.getLogger(SetupController::class.java)

    fun applyMany(namespace: String, openShiftObjects: List<JsonNode>, token: String): List<OpenShiftResponse> {

        return openShiftObjects.map {
            Thread.sleep(1000)
            //race condition if we create resources to fast
            apply(namespace, it, token)
        }
    }

    fun apply(namespace: String, json: JsonNode, token: String): OpenShiftResponse {

        val urls: OpenShiftApiUrls = OpenShiftApiUrls.createUrlsForResource(baseUrl, namespace, json)
        val headers: HttpHeaders = createHeaders(token)

        val existingResource: ResponseEntity<JsonNode>? = getExistingResource(headers, urls.get)
        return if (existingResource != null) {
            logger.info("Resource ${urls.get} already exists. Skipping...")
            OpenShiftResponse(OperationType.NONE, json, existingResource.body)
        } else {
            logger.info("Creating resource ${urls.get}")
            val createdResource = createResource(headers, urls.update, json)
            OpenShiftResponse(OperationType.CREATED, json, createdResource.body)
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
        } catch(e: HttpClientErrorException) {
            if (e.statusCode != HttpStatus.NOT_FOUND) {
                throw OpenShiftException("An unexpected error occurred when getting resource $url", e)
            }
            null
        }
    }

    private fun createResource(headers: HttpHeaders, updateUrl: String, payload: JsonNode): ResponseEntity<JsonNode> {
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

    fun updateRoleBinding(namespace: String, role: String, token: String, users: List<String>, groups: List<String>): OpenShiftResponse? {
        val url: OpenShiftApiUrls = OpenShiftApiUrls.createOpenShiftApiUrls(baseUrl, "rolebinding", namespace, role)
        val headers: HttpHeaders = createHeaders(token)

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
}
