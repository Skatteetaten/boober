package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.controller.SetupController
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.*
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate
import java.net.URI


data class OpenShiftResponse(
        val payload: JsonNode,
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

        val resource: ResponseEntity<JsonNode> = getExistingResource(headers, urls.get)
                ?: createResource(headers, urls.update, json)

        return OpenShiftResponse(json, resource.body)
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
}
