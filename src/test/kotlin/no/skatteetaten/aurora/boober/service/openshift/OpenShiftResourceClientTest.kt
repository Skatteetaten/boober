package no.skatteetaten.aurora.boober.service.openshift

import assertk.assert
import assertk.assertions.isEqualTo
import com.fasterxml.jackson.databind.node.TextNode
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.service.openshift.token.TokenProvider
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import org.springframework.web.client.RestTemplate

class OpenShiftResourceClientTest {

    private val server = MockWebServer()
    private val baseUrl = server.url("/")

    private val tokenProvider = mockk<TokenProvider>().apply {
        every { getToken() } returns "token"
    }
    private val openShiftRestTemplateWrapper =
        OpenShiftRestTemplateWrapper(RestTemplate(HttpComponentsClientHttpRequestFactory()))

    private val openShiftResourceClient = OpenShiftResourceClient(
        baseUrl.toString(),
        tokenProvider,
        openShiftRestTemplateWrapper
    )

    @Test
    fun `Patch user annotation`() {
        val response = """{ "metadata": { "annotations": { "foo": "bar" } } }"""

        val request = server.execute(response) {
            val responseEntity = openShiftResourceClient.strategicMergePatch("user", "username", TextNode("{}"))
            assert(responseEntity.statusCode.value()).isEqualTo(200)
        }
        assert(request.headers[HttpHeaders.CONTENT_TYPE]).isEqualTo("application/strategic-merge-patch+json")
        assert(request.path).isEqualTo("/oapi/v1/users/username")
    }
}