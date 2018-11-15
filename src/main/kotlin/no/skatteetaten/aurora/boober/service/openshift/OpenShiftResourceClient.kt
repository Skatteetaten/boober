package no.skatteetaten.aurora.boober.service.openshift

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.service.OpenShiftException
import no.skatteetaten.aurora.boober.service.openshift.token.TokenProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.RequestEntity
import org.springframework.http.ResponseEntity
import org.springframework.web.client.HttpClientErrorException
import java.net.URI

open class OpenShiftResourceClient(
    @Value("\${openshift.url}") val baseUrl: String,
    val tokenProvider: TokenProvider,
    val restTemplateWrapper: OpenShiftRestTemplateWrapper
) {

    fun put(kind: String, namespace: String, name: String, payload: JsonNode): ResponseEntity<JsonNode> {
        val urls: OpenShiftApiUrls = OpenShiftApiUrls.createOpenShiftApiUrls(baseUrl, kind, namespace, name)
        val headers: HttpHeaders = getAuthorizationHeaders()
        return restTemplateWrapper.exchange(RequestEntity<JsonNode>(payload, headers, HttpMethod.PUT, URI(urls.update)))
    }

    open fun get(kind: String, namespace: String, name: String, retry: Boolean = true): ResponseEntity<JsonNode>? {

        val urls: OpenShiftApiUrls = OpenShiftApiUrls.createOpenShiftApiUrls(baseUrl, kind, namespace, name)
        val url = urls.get ?: return null

        return get(url, retry = retry)
    }

    open fun get(url: String, headers: HttpHeaders = getAuthorizationHeaders(), retry: Boolean = true) =
        exchange<JsonNode>(RequestEntity(headers, HttpMethod.GET, URI(url)), retry)

    open fun post(kind: String, namespace: String, name: String? = null, payload: JsonNode): ResponseEntity<JsonNode> {

        val urls: OpenShiftApiUrls = OpenShiftApiUrls.createOpenShiftApiUrls(baseUrl, kind, namespace, name)
        val headers: HttpHeaders = getAuthorizationHeaders()
        return exchange(RequestEntity<JsonNode>(payload, headers, HttpMethod.POST, URI(urls.create)))!!
    }

    fun delete(kind: String, namespace: String, name: String? = null): ResponseEntity<JsonNode> {

        val urls: OpenShiftApiUrls = OpenShiftApiUrls.createOpenShiftApiUrls(baseUrl, kind, namespace, name)
        val headers: HttpHeaders = getAuthorizationHeaders()
        return exchange(RequestEntity<JsonNode>(headers, HttpMethod.DELETE, URI(urls.get)))!!
    }

    fun patch(kind: String, name: String? = null, payload: JsonNode): ResponseEntity<JsonNode> {
        val urls = OpenShiftApiUrls.createOpenShiftApiUrls(baseUrl = baseUrl, kind = kind, name = name)
        val headers = getAuthorizationHeaders().apply {
            set(HttpHeaders.CONTENT_TYPE, "application/json-patch+json")
        }
        return exchange(RequestEntity<JsonNode>(payload, headers, HttpMethod.PATCH, URI(urls.update)))!!
    }

    open fun getAuthorizationHeaders(): HttpHeaders {
        return createHeaders(tokenProvider.getToken())
    }

    fun createHeaders(token: String) =
        HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("Authorization", "Bearer $token")
        }

    private fun <T> exchange(requestEntity: RequestEntity<T>, retry: Boolean = true) = try {
        restTemplateWrapper.exchange(requestEntity, retry)
    } catch (e: HttpClientErrorException) {
        if (e.statusCode != HttpStatus.NOT_FOUND) {
            throw OpenShiftException("An error occurred while communicating with OpenShift", e)
        }
        null
    }
}