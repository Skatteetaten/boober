package no.skatteetaten.aurora.boober.service.openshift

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.internal.OpenShiftException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.RequestEntity
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate
import java.net.URI

@Service
class OpenShiftResourceClient(@Value("\${openshift.url}") val baseUrl: String,
                              val userDetailsProvider: UserDetailsProvider,
                              val restTemplate: RestTemplate) {

    val logger: Logger = LoggerFactory.getLogger(OpenShiftResourceClient::class.java)


    fun put(kind: String, name: String, namespace: String, payload: JsonNode): ResponseEntity<JsonNode> {
        val urls: OpenShiftApiUrls = OpenShiftApiUrls.createOpenShiftApiUrls(baseUrl, kind, name, namespace)
        val headers: HttpHeaders = getAuthorizationHeaders()
        return exchange(RequestEntity<JsonNode>(payload, headers, HttpMethod.PUT, URI(urls.update)))
    }

    fun get(kind: String, name: String, namespace: String): ResponseEntity<JsonNode>? {
        val urls: OpenShiftApiUrls = OpenShiftApiUrls.createOpenShiftApiUrls(baseUrl, kind, name, namespace)
        if (urls.get == null) {
            return null
        }
        val headers: HttpHeaders = getAuthorizationHeaders()

        try {
            return exchange(RequestEntity<Any>(headers, HttpMethod.GET, URI(urls.get)))
        } catch(e: OpenShiftException) {
            if (e.cause is HttpClientErrorException && e.cause.statusCode == HttpStatus.NOT_FOUND) {
                return null
            }
            throw e
        }
    }

    fun post(kind: String, name: String? = null, namespace: String, payload: JsonNode): ResponseEntity<JsonNode> {

        val urls: OpenShiftApiUrls = OpenShiftApiUrls.createOpenShiftApiUrls(baseUrl, kind, name, namespace)
        val headers: HttpHeaders = getAuthorizationHeaders()
        return exchange(RequestEntity<JsonNode>(payload, headers, HttpMethod.POST, URI(urls.create)))
    }

    fun delete(headers: HttpHeaders, url: String): ResponseEntity<JsonNode>? {
        val requestEntity = RequestEntity<Any>(headers, HttpMethod.DELETE, URI(url))
        return restTemplate.exchange(requestEntity, JsonNode::class.java)
    }

    fun getExistingResource(headers: HttpHeaders, url: String): ResponseEntity<JsonNode>? {
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

    fun getAuthorizationHeaders(): HttpHeaders {
        return createHeaders(userDetailsProvider.getAuthenticatedUser().token)
    }

    fun createHeaders(token: String): HttpHeaders {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        headers.set("Authorization", "Bearer " + token)
        return headers
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
}