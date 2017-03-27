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
import org.springframework.web.client.RestTemplate


@Service
class OpenshiftClient(@Value("\${openshift.url}") val baseUrl: String, val client: RestTemplate) {

    val logger: Logger = LoggerFactory.getLogger(ExecuteController::class.java)

    fun save(url: String, json: JsonNode, token: String): ResponseEntity<JsonNode>? {

        val headers = createHeaders(token)
        val entity = HttpEntity<JsonNode>(json, headers)
        val fullUrl = baseUrl + url

        val res = client.postForEntity(fullUrl, entity, JsonNode::class.java)
        logger.info("Saving resource to $fullUrl with response code ${res.statusCodeValue}")
        logger.debug("Body=${res.body}")
        return res
    }

    private fun createHeaders(token: String): HttpHeaders {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        headers.set("Authorization", "Bearer " + token)
        return headers
    }
}