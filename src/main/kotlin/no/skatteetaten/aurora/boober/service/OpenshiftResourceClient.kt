package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.RequestEntity
import org.springframework.http.ResponseEntity
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate
import java.net.URI


class OpenshiftResourceClient(val urls: OpenShiftApiUrls,
                              val headers: HttpHeaders,
                              val restTemplate: RestTemplate,
                              val dryRun: Boolean) {

    val logger: Logger = LoggerFactory.getLogger(OpenshiftResourceClient::class.java)

    fun put(payload: JsonNode): ResponseEntity<JsonNode>? {
        return exchange(RequestEntity<JsonNode>(payload, headers, HttpMethod.PUT, URI(urls.update)))
    }


    fun get(): ResponseEntity<JsonNode>? {
        return exchange(RequestEntity<Any>(headers, HttpMethod.GET, URI(urls.get)))

    }

    fun post(payload: JsonNode): ResponseEntity<JsonNode>? {
        return exchange(RequestEntity<JsonNode>(payload, headers, HttpMethod.POST, URI(urls.create)))
    }

    private fun <T> exchange(requestEntity: RequestEntity<T>): ResponseEntity<JsonNode>? {
        if (dryRun) {
            logger.debug("Dry run ${requestEntity.method} to ${requestEntity.url}")
            return null
        }
        logger.info("${requestEntity.method} resource at ${requestEntity.url}")

        val createResponse: ResponseEntity<JsonNode> = try {
            restTemplate.exchange(requestEntity, JsonNode::class.java)
        } catch(e: HttpClientErrorException) {
            throw OpenShiftException("Error saving url=${urls.create}, with message=${e.message}", e)
        }
        logger.debug("Body=${createResponse.body}")
        return createResponse
    }

}