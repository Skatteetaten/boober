package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.controller.ExecuteController
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate


data class OpenShiftResponse(
        val payload: JsonNode,
        val responseBody: JsonNode?
)

@Service
class OpenShiftClient(@Value("\${openshift.url}") val baseUrl: String, val client: RestTemplate) {

    val logger: Logger = LoggerFactory.getLogger(ExecuteController::class.java)

    fun saveMany(namespace: String, openShiftObjects: List<JsonNode>, token: String): List<OpenShiftResponse> {

        return openShiftObjects.map {
            Thread.sleep(1000)
            //race condition if we create resources to fast
            save(namespace, it, token)
        }
    }

    fun save(namespace: String, json: JsonNode, token: String): OpenShiftResponse {

        val kind = json.get("kind").asText()
        val url = createOpenShiftApiUrl(kind, namespace)

        val headers = createHeaders(token)
        val entity = HttpEntity<JsonNode>(json, headers)
        val fullUrl = baseUrl + url

        val res: ResponseEntity<JsonNode>?
        try {
            res = client.postForEntity(fullUrl, entity, JsonNode::class.java)
        } catch(e: HttpClientErrorException) {
            val message = "Error saving url=$url, with message=${e.message}"
            throw AocException(message, e)
        }
        logger.info("Saving resource to $fullUrl with responseBody code ${res.statusCodeValue}")
        logger.debug("Body=${res.body}")

        val httpResult: JsonNode? = res?.body
        return OpenShiftResponse(json, httpResult)
    }

    private fun createHeaders(token: String): HttpHeaders {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        headers.set("Authorization", "Bearer " + token)
        return headers
    }

    private fun createOpenShiftApiUrl(kind: String, namespace: String): String {

        val endpointKey = kind.toLowerCase() + "s"

        if (endpointKey == "projects") {
            return "/oapi/v1/projects"
        }

        val prefix = if (endpointKey in listOf("services", "configmaps")) {
            "/api"
        } else {
            "/oapi"
        }

        return "$prefix/v1/namespaces/$namespace/$endpointKey"
    }
}