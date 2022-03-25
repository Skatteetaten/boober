package no.skatteetaten.aurora.boober.service.openshift

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.service.OpenShiftException
import no.skatteetaten.aurora.boober.service.openshift.token.TokenProvider
import no.skatteetaten.aurora.boober.utils.findApiVersion
import no.skatteetaten.aurora.boober.utils.findOpenShiftApiPrefix
import no.skatteetaten.aurora.boober.utils.kindsWithoutNamespace
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.RequestEntity
import org.springframework.http.ResponseEntity
import org.springframework.web.client.HttpClientErrorException
import java.net.URI

private val logger = KotlinLogging.logger {}

// TODO: Bytte ut denne med den WebClient baserte klienten fra Mokey
open class OpenShiftResourceClient(
    val tokenProvider: TokenProvider,
    val restTemplateWrapper: OpenShiftRestTemplateWrapper
) {

    fun put(url: String, payload: JsonNode): ResponseEntity<JsonNode> {
        val headers: HttpHeaders = getAuthorizationHeaders()
        return exchange(RequestEntity(payload, headers, HttpMethod.PUT, URI(url)))
            ?: throw OpenShiftException("Put request with url=$url returned null")
    }

    open fun get(
        kind: String,
        namespace: String? = null,
        name: String,
        retry: Boolean = true
    ): ResponseEntity<JsonNode>? {
        if (kind in listOf("deploymentrequest", "processedtemplate")) return null
        val url = generateUrl(kind, namespace, name)
        return get(url, retry = retry)
    }

    open fun get(url: String, headers: HttpHeaders = getAuthorizationHeaders(), retry: Boolean = true) =
        exchange<JsonNode>(RequestEntity(headers, HttpMethod.GET, URI(url)), retry)

    open fun post(url: String, payload: JsonNode): ResponseEntity<JsonNode> {
        val headers: HttpHeaders = getAuthorizationHeaders()

        return exchange(RequestEntity(payload, headers, HttpMethod.POST, URI(url)))
            ?: throw OpenShiftException("Post request with url=$url returned null")
    }

    fun delete(url: String): ResponseEntity<JsonNode>? {
        val headers: HttpHeaders = getAuthorizationHeaders()
        return exchange(RequestEntity<JsonNode>(headers, HttpMethod.DELETE, URI(url)), retry = false)
    }

    fun strategicMergePatch(kind: String, name: String? = null, payload: JsonNode): ResponseEntity<JsonNode> {
        val url = generateUrl(kind, name = name)
        val headers = getAuthorizationHeaders().apply {
            set(HttpHeaders.CONTENT_TYPE, "application/strategic-merge-patch+json")
        }
        return exchange(RequestEntity<JsonNode>(payload, headers, HttpMethod.PATCH, URI(url)))
            ?: throw OpenShiftException("Patch request with url=$url returned null")
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
        if (!e.statusCode.is4xxClientError) {

            val message = try {
                val jsonError = jacksonObjectMapper().readTree(e.responseBodyAsByteArray)
                if (jsonError.has("message")) {
                    jsonError["message"].textValue()
                } else {
                    e.responseBodyAsString
                }
            } catch (err: Exception) {
                e.responseBodyAsString
            }

            logger.info("Failed communicating with openShift code=${e.statusCode} message=${e.message}")
            throw OpenShiftException(
                "openShiftCommunicationError code=${e.statusCode.value()} message=${e.message} error=$message",
                e
            )
        }
        logger.info("Request with url=${requestEntity.url} responded with code=${e.statusCode} message=${e.message}")
        null
    }

    companion object {
        fun generateUrl(kind: String, namespace: String? = null, name: String? = null): String {

            val kinds = kind.lowercase() + "s"

            if (kinds !in kindsWithoutNamespace && namespace == null) {
                throw IllegalArgumentException("namespace required for resource kind $kind")
            }

            val namespaceSegment = namespace?.let { "/namespaces/$namespace" } ?: ""

            val apiVersion = findApiVersion(kind)
            val apiPrefix = findOpenShiftApiPrefix(apiVersion, kind)

            val namePart = name?.let { "/$name" } ?: ""
            return "/$apiPrefix/$apiVersion$namespaceSegment/$kinds$namePart"
        }
    }
}
