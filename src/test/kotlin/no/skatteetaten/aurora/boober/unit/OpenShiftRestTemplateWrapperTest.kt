package no.skatteetaten.aurora.boober.unit

import assertk.all
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftRestTemplateWrapper
import no.skatteetaten.aurora.boober.utils.ResourceLoader
import no.skatteetaten.aurora.boober.utils.RetryLogger
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.execute
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.RequestEntity
import org.springframework.http.ResponseEntity
import org.springframework.web.client.HttpClientErrorException
import java.net.URI

class OpenShiftRestTemplateWrapperTest : ResourceLoader() {
    private val server = MockWebServer()
    private val baseUrl = server.url("/")
    private val restTemplate = RestTemplateBuilder().rootUri(baseUrl.toString()).build()
    private val restTemplateWrapper =
        OpenShiftRestTemplateWrapper(restTemplate)

    val resourceUrl = "$baseUrl/apis/apps.openshift.io/v1/namespaces/aos/deploymentconfigs/webleveranse"

    @Test
    fun `Succeeds even if the request fails a couple of times`() {

        val resourceMergerTestName = ResourceMergerTest::class.simpleName!!
        val resource = loadJsonResource("deploymentconfig.json", folder = "$packageName/$resourceMergerTestName")

        server.execute(
            400 to "",
            400 to "",
            200 to resource
        ) {
            val entity: ResponseEntity<JsonNode> = restTemplateWrapper.exchange(
                RequestEntity<Any>(HttpMethod.GET, URI(resourceUrl)), true
            )

            assertThat(resource).jsonEquals(entity.body!!, "ResourceMergerTest/deploymentconfig.json", allowOverwrite = false)
        }
    }

    @Test
    fun `Fails when exceeds retry attempts`() {
        server.execute(
            400 to "",
            400 to "",
            400 to ""
        ) {
            assertThat {
                restTemplateWrapper.exchange(
                    RequestEntity<Any>(HttpMethod.GET, URI(resourceUrl)), true
                )
            }.isFailure().all {
                this.isInstanceOf(HttpClientErrorException::class)
            }
        }
    }

    @Test
    fun `Fails immediately when retry is disabled`() {

        server.execute(
            400 to ""
        ) {
            assertThat {
                restTemplateWrapper.exchange(
                    RequestEntity<Any>(HttpMethod.GET, URI(resourceUrl)), false
                )
            }.isFailure().all {
                this.isInstanceOf(HttpClientErrorException::class)
            }
            // TOOD: hvordan kan jeg her sjekke at den ikke gjør flere kall?
        }
    }

    @Test
    fun `Get token snippet from auth header`() {

        val token = "some_long_token"
        val snippet = "token"
        val httpHeaders = HttpHeaders().apply {
            add(HttpHeaders.AUTHORIZATION, "Authorization $token")
        }
        assertThat(RetryLogger.getTokenSnippetFromAuthHeader(httpHeaders)).isEqualTo(snippet)
    }

    @Test
    fun `Get sha256 token snippet from auth header`() {

        val token = "sha256~09876"
        val snippet = "09876"
        val httpHeaders = HttpHeaders().apply {
            add(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }
        assertThat(RetryLogger.getTokenSnippetFromAuthHeader(httpHeaders)).isEqualTo(snippet)
    }

    @Test
    fun `Get token snippet from short token in auth header`() {

        val token = "9876"
        val snippet = "9876"
        val httpHeaders = HttpHeaders().apply {
            add(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }
        assertThat(RetryLogger.getTokenSnippetFromAuthHeader(httpHeaders)).isEqualTo(snippet)
    }
}
