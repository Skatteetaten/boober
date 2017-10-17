package no.skatteetaten.aurora.boober.service.openshift

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.service.OpenShiftException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.*
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate
import java.net.URI

open class OpenShiftResourceClient(@Value("\${openshift.url}") val baseUrl: String,
                                   val tokenProvider: TokenProvider,
                                   val restTemplate: RestTemplate) {

    val logger: Logger = LoggerFactory.getLogger(OpenShiftResourceClient::class.java)


    fun put(kind: String, namespace: String, name: String, payload: JsonNode): ResponseEntity<JsonNode> {
        val urls: OpenShiftApiUrls = OpenShiftApiUrls.createOpenShiftApiUrls(baseUrl, kind, namespace, name)
        val headers: HttpHeaders = getAuthorizationHeaders()
        return exchange(RequestEntity<JsonNode>(payload, headers, HttpMethod.PUT, URI(urls.update)))
    }

    open fun get(kind: String, namespace: String, name: String): ResponseEntity<JsonNode>? {
        val urls: OpenShiftApiUrls = OpenShiftApiUrls.createOpenShiftApiUrls(baseUrl, kind, namespace, name)
        if (urls.get == null) {
            return null
        }
        val headers: HttpHeaders = getAuthorizationHeaders()

        try {
            return exchange(RequestEntity<Any>(headers, HttpMethod.GET, URI(urls.get)))
        } catch (e: OpenShiftException) {
            if (e.cause is HttpClientErrorException && e.cause.statusCode == HttpStatus.NOT_FOUND) {
                return null
            }
            throw e
        }
    }

    open fun post(kind: String, namespace: String, name: String? = null, payload: JsonNode): ResponseEntity<JsonNode> {

        val urls: OpenShiftApiUrls = OpenShiftApiUrls.createOpenShiftApiUrls(baseUrl, kind, namespace, name)
        val headers: HttpHeaders = getAuthorizationHeaders()
        return exchange(RequestEntity<JsonNode>(payload, headers, HttpMethod.POST, URI(urls.create)))
    }

    fun delete(kind: String, namespace: String, name: String? = null): ResponseEntity<JsonNode> {

        val urls: OpenShiftApiUrls = OpenShiftApiUrls.createOpenShiftApiUrls(baseUrl, kind, namespace, name)
        val headers: HttpHeaders = getAuthorizationHeaders()
        return exchange(RequestEntity<JsonNode>(headers, HttpMethod.DELETE, URI(urls.get)))
    }

    fun delete(headers: HttpHeaders, url: String): ResponseEntity<JsonNode>? {
        val requestEntity = RequestEntity<Any>(headers, HttpMethod.DELETE, URI(url))
        return restTemplate.exchange(requestEntity, JsonNode::class.java)
    }

    open fun getExistingResource(headers: HttpHeaders, url: String): ResponseEntity<JsonNode>? {
        return try {
            val requestEntity = RequestEntity<Any>(headers, HttpMethod.GET, URI(url))
            restTemplate.exchange(requestEntity, JsonNode::class.java)
        } catch (e: Exception) {
            if (e is HttpClientErrorException && e.statusCode != HttpStatus.NOT_FOUND) {
                throw OpenShiftException("An unexpected error occurred when getting resource $url", e)
            }
            null
        }
    }

    open fun getAuthorizationHeaders(): HttpHeaders {
        return createHeaders(tokenProvider.getToken())
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
        } catch (e: HttpClientErrorException) {
            throw OpenShiftException("Request failed url=${requestEntity.url}, method=${requestEntity.method}, message=${e.message}, code=${e.statusCode.value()}", e)
        }
        logger.debug("Body=${createResponse.body}")
        return createResponse
    }
}