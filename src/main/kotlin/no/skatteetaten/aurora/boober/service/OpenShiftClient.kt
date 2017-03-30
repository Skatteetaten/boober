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
class OpenShiftClient(@Value("\${openshift.url}") val baseUrl: String, val client: RestTemplate) {

    val logger: Logger = LoggerFactory.getLogger(SetupController::class.java)

    fun saveMany(namespace: String, openShiftObjects: List<JsonNode>, token: String): List<OpenShiftResponse> {

        return openShiftObjects.map {
            Thread.sleep(1000)
            //race condition if we create resources to fast
            save(namespace, it, token)
        }
    }

    fun save(namespace: String, json: JsonNode, token: String): OpenShiftResponse {

        val kind = json.get("kind")?.asText() ?: throw IllegalArgumentException("kind not specified for resource")
        val name = json.get("metadata")?.get("name")?.asText() ?: throw IllegalArgumentException("name not specified for resource")

        val urls: Urls = createOpenShiftApiUrls(kind, namespace, name)
        val headers: HttpHeaders = createHeaders(token)

        val existingResource: ResponseEntity<JsonNode>? = try {
            val requestEntity = RequestEntity<Any>(headers, HttpMethod.GET, URI(urls.get))
            client.exchange(requestEntity, JsonNode::class.java)
        } catch(e: HttpClientErrorException) {
            if (e.statusCode != HttpStatus.NOT_FOUND) {
                throw OpenShiftException("An unexpected error occurred when getting resource ${urls.get}", e)
            }
            null
        }
        if (existingResource != null) {
            logger.info("Resource ${urls.get} already exists. Skipping..")
            return OpenShiftResponse(json, existingResource.body)
        }

        val entity = HttpEntity<JsonNode>(json, headers)
        val createResponse: ResponseEntity<JsonNode> = try {
            client.postForEntity(urls.update, entity, JsonNode::class.java)
        } catch(e: HttpClientErrorException) {
            throw OpenShiftException("Error saving url=$urls, with message=${e.message}", e)
        }
        logger.debug("Body=${createResponse.body}")

        return OpenShiftResponse(json, createResponse.body)
    }

    private fun createHeaders(token: String): HttpHeaders {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        headers.set("Authorization", "Bearer " + token)
        return headers
    }

    data class Urls(
            val update: String,
            val get: String
    )

    private fun createOpenShiftApiUrls(kind: String, namespace: String, name: String): Urls {

        val endpointKey = kind.toLowerCase() + "s"

        if (endpointKey == "projects") {
            return Urls(
                    update = "$baseUrl/oapi/v1/projects",
                    get = "$baseUrl/oapi/v1/projects/$name"
            )
        }

        val prefix = if (endpointKey in listOf("services", "configmaps")) {
            "/api"
        } else {
            "/oapi"
        }

        return Urls(
                update = "$baseUrl/$prefix/v1/namespaces/$namespace/$endpointKey",
                get = "$baseUrl/$prefix/v1/namespaces/$namespace/$endpointKey/$name"
        )
    }
}