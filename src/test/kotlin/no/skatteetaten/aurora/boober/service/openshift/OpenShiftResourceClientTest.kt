package no.skatteetaten.aurora.boober.service.openshift

import assertk.assert
import assertk.assertions.isEqualTo
import com.fasterxml.jackson.databind.node.TextNode
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.service.openshift.token.TokenProvider
import okhttp3.mockwebserver.MockWebServer
import org.junit.Ignore
import org.junit.jupiter.api.Test
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.http.HttpHeaders

class OpenShiftResourceClientTest {

    private val server = MockWebServer()
    private val baseUrl = server.url("/")

    private val tokenProvider = mockk<TokenProvider>().apply {
        every { getToken() } returns "token"
    }
    private val restTemplate = RestTemplateBuilder().rootUri(baseUrl.toString()).build()
    private val openShiftRestTemplateWrapper = OpenShiftRestTemplateWrapper(restTemplate)

    private val openShiftResourceClient = OpenShiftResourceClient(
        tokenProvider,
        openShiftRestTemplateWrapper
    )

    @Ignore("Can not get rootUri on restTemplate to work with mockWebServer")
    @Test
    fun `Patch user annotation`() {
        val response = """{ "metadata": { "annotations": { "foo": "bar" } } }"""

        val request = server.execute(response) {
            val responseEntity = openShiftResourceClient.strategicMergePatch("user", "username", TextNode("{}"))
            assert(responseEntity.statusCode.value()).isEqualTo(200)
        }
        assert(request.headers[HttpHeaders.CONTENT_TYPE]).isEqualTo("application/strategic-merge-patch+json")
        assert(request.path).isEqualTo("/apis/user.openshift.io/v1/users/username")
    }
}