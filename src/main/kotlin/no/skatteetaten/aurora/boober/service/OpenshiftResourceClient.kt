package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.*
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate
import java.net.URI

@Service
class OpenshiftResourceClient(@Value("\${openshift.url}") val baseUrl: String,
                              val userDetailsProvider: UserDetailsProvider,
                              val restTemplate: RestTemplate) {

    val logger: Logger = LoggerFactory.getLogger(OpenshiftResourceClient::class.java)

    fun put(kind: String, name: String, namespace: String, payload: JsonNode): ResponseEntity<JsonNode> {
        val urls: OpenShiftApiUrls = OpenShiftApiUrls.createOpenShiftApiUrls(baseUrl, kind, name, namespace)
        val headers: HttpHeaders = createHeaders(userDetailsProvider.getAuthenticatedUser().token)
        return exchange(RequestEntity<JsonNode>(payload, headers, HttpMethod.PUT, URI(urls.update)))
    }


    fun get(kind: String, name: String, namespace: String): ResponseEntity<JsonNode>? {
        val urls: OpenShiftApiUrls = OpenShiftApiUrls.createOpenShiftApiUrls(baseUrl, kind, name, namespace)
        if (urls.get == null) {
            return null
        }
        val headers: HttpHeaders = createHeaders(userDetailsProvider.getAuthenticatedUser().token)

        try {
            return exchange(RequestEntity<Any>(headers, HttpMethod.GET, URI(urls.get)))
        } catch(e: OpenShiftException) {
            if (e.cause is HttpClientErrorException && e.cause.statusCode == HttpStatus.NOT_FOUND) {
                return null
            }
            throw e
        }
    }

    fun post(kind: String, name: String, namespace: String, payload: JsonNode): ResponseEntity<JsonNode> {

        val urls: OpenShiftApiUrls = OpenShiftApiUrls.createOpenShiftApiUrls(baseUrl, kind, name, namespace)
        val headers: HttpHeaders = createHeaders(userDetailsProvider.getAuthenticatedUser().token)
        return exchange(RequestEntity<JsonNode>(payload, headers, HttpMethod.POST, URI(urls.create)))
    }

    private fun <T> exchange(requestEntity: RequestEntity<T>): ResponseEntity<JsonNode> {
        logger.info("${requestEntity.method} resource at ${requestEntity.url}")

        val createResponse: ResponseEntity<JsonNode> = try {
            restTemplate.exchange(requestEntity, JsonNode::class.java)
        } catch(e: HttpClientErrorException) {
            throw OpenShiftException("Error saving url=${requestEntity.url}, with message=${e.message}", e)
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