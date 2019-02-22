package no.skatteetaten.aurora.boober.service.openshift

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.service.OpenShiftException
import no.skatteetaten.aurora.boober.service.openshift.token.TokenProvider
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.RequestEntity
import org.springframework.http.ResponseEntity
import org.springframework.web.client.HttpClientErrorException
import java.net.URI

open class OpenShiftResourceClient(
    val tokenProvider: TokenProvider,
    val restTemplateWrapper: OpenShiftRestTemplateWrapper
) {

    fun put(url: String, payload: JsonNode): ResponseEntity<JsonNode> {
        val headers: HttpHeaders = getAuthorizationHeaders()
        return restTemplateWrapper.exchange(RequestEntity<JsonNode>(payload, headers, HttpMethod.PUT, URI(url)))
    }

    open fun get(
        kind: String,
        namespace: String? = null,
        name: String,
        retry: Boolean = true
    ): ResponseEntity<JsonNode>? {
        //if deploymentrequest or processedtemplates return null,
        if (kind in listOf("deploymentrequest", "processedtemplate")) return null
        val url = generateUrl(kind, namespace, name)
        return get(url, retry = retry)
    }

    open fun get(url: String, headers: HttpHeaders = getAuthorizationHeaders(), retry: Boolean = true) =
        exchange<JsonNode>(RequestEntity(headers, HttpMethod.GET, URI(url)), retry)

    open fun post(url: String, payload: JsonNode): ResponseEntity<JsonNode> {
        val headers: HttpHeaders = getAuthorizationHeaders()
        return exchange(RequestEntity<JsonNode>(payload, headers, HttpMethod.POST, URI(url)))!!
    }

    fun delete(url: String): ResponseEntity<JsonNode> {
        val headers: HttpHeaders = getAuthorizationHeaders()
        return exchange(RequestEntity<JsonNode>(headers, HttpMethod.DELETE, URI(url)))!!
    }

    fun strategicMergePatch(kind: String, name: String? = null, payload: JsonNode): ResponseEntity<JsonNode> {
        val url = generateUrl(kind, name = name)
        val headers = getAuthorizationHeaders().apply {
            set(HttpHeaders.CONTENT_TYPE, "application/strategic-merge-patch+json")
        }
        return exchange(RequestEntity<JsonNode>(payload, headers, HttpMethod.PATCH, URI(url)))!!
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

    companion object {
        @JvmStatic
        @JvmOverloads
        fun generateUrl(kind: String, namespace: String? = null, name: String? = null): String {

            //So fun with consistent apis. NOT!
            val kinds = if (kind == "deploymentrequest") {
                "deploymentconfigs"
            } else {
                kind.toLowerCase() + "s"
            }

            val kindsWithoutNamespace = listOf(
                "namespaces",
                "projects",
                "projectrequests",
                "deploymentreqeusts",
                "users",
                "groups"
            )
            if (kinds !in kindsWithoutNamespace && namespace == null) {
                throw IllegalArgumentException("namespace required for resource kind $kind")
            }

            val namespaceSegment = namespace?.let { "/namespaces/$namespace" } ?: ""

            val apiSegment = when (kind.toLowerCase()) {
                "applicationdeployment" -> "apis/skatteetaten.no/v1"
                "deploymentconfig" -> "apis/apps.openshift.io/v1"
                "deploymentrequest" -> "apis/apps.openshift.io/v1"
                "route" -> "apis/route.openshift.io/v1"
                "user" -> "apis/user.openshift.io/v1"
                "project" -> "apis/project.openshift.io/v1"
                "template" -> "apis/template.openshift.io/v1"
                "projectrequest" -> "apis/project.openshift.io/v1"
                "imagestream" -> "apis/image.openshift.io/v1"
                "imagestreamtag" -> "apis/image.openshift.io/v1"
                "imagestreamimport" -> "apis/image.openshift.io/v1"
                "rolebinding" -> "apps/image.openshift.io/v1"
                "group" -> "apis/user.openshift.io/v1"
                "buildconfig" -> "apis/build.openshift.io/v1"
                "processedtemplate" -> "oapi/v1"
                else -> "api/v1"

            }

            //TODO: I think this can go away
            val namePart = name?.let {
                if (kind.toLowerCase() == "deploymentrequest") {
                    "/$name/instantiate"
                } else {
                    "/$name"
                }
            } ?: ""
            return "/$apiSegment$namespaceSegment/$kinds$namePart"
        }
    }
}