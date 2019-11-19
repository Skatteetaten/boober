package no.skatteetaten.aurora.boober.unit

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import com.fasterxml.jackson.databind.node.TextNode
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftRestTemplateWrapper
import no.skatteetaten.aurora.boober.service.openshift.token.TokenProvider
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.execute
import okhttp3.mockwebserver.MockWebServer
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
    private val openShiftRestTemplateWrapper =
        OpenShiftRestTemplateWrapper(restTemplate)

    private val openShiftResourceClient = OpenShiftResourceClient(
        tokenProvider,
        openShiftRestTemplateWrapper
    )

    @Test
    fun `Patch user annotation`() {
        val response = """{ "metadata": { "annotations": { "foo": "bar" } } }"""

        val request = server.execute(response) {
            val responseEntity = openShiftResourceClient.strategicMergePatch("user", "username", TextNode("{}"))
            assertThat(responseEntity.statusCode.value()).isEqualTo(200)
        }
        assertThat(request.first()?.headers?.get(HttpHeaders.CONTENT_TYPE)).isNotNull()
            .isEqualTo("application/strategic-merge-patch+json")
        assertThat(request.first()?.path).isNotNull().isEqualTo("/apis/user.openshift.io/v1/users/username")
    }
}
