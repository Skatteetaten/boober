package no.skatteetaten.aurora.boober.unit

import org.apache.http.HttpStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.web.client.RestTemplateBuilder
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isSuccess
import assertk.assertions.isTrue
import io.mockk.clearAllMocks
import no.skatteetaten.aurora.boober.service.AuroraResponse
import no.skatteetaten.aurora.boober.service.CantusRestTemplateWrapper
import no.skatteetaten.aurora.boober.service.CantusService
import no.skatteetaten.aurora.boober.service.CantusServiceException
import no.skatteetaten.aurora.boober.service.ImageTagResource
import no.skatteetaten.aurora.boober.service.TagCommand
import no.skatteetaten.aurora.boober.utils.jsonMapper
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.execute
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.url
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class CantusServiceTest {

    private val server = MockWebServer()
    val service = CantusService(
        CantusRestTemplateWrapper(
            RestTemplateBuilder().rootUri(server.url).build(),
            0
        ),
        "http://docker.com"
    )
    val mapper = jsonMapper()

    @BeforeEach
    fun setup() {
        clearAllMocks()
    }

    @Test
    fun `Should tag from one tag to another`() {
        val manifest: JsonNode = mapper.convertValue(mapOf("manifest" to "foo"))

        server.execute(manifest) {
            val response = service.tag(
                TagCommand(
                    "foo/bar",
                    "1.2.3",
                    "latest",
                    "registry"
                )
            )
            assertThat(response.success).isTrue()
        }
    }

    @Test
    fun `Should record failure`() {
        val manifest: JsonNode = mapper.convertValue(mapOf("manifest" to "foo"))

        server.execute(400 to manifest) {
            val response = service.tag(
                TagCommand(
                    "foo/bar",
                    "1.2.3",
                    "latest",
                    "registry"
                )
            )
            assertThat(response.success).isFalse()
        }
    }

    @Test
    fun `should receive manifest`() {
        server.execute(createImageTageResourceResponse()) {
            assertThat {
                service.getImageInformation(
                    "bar",
                    "foo",
                    "latest"
                )
            }.isSuccess()
                .given {
                    assertThat(it).isNotEmpty()
                    assertThat(it.first().dockerDigest).isEqualTo("sha256:1234")
                }
        }
    }

    @Test
    fun `should throw when failure in response`() {
        server.execute(createImageTageResourceResponse(success = false)) {
            assertThat {
                service.getImageInformation(
                    "bar",
                    "foo",
                    "latest"
                )
            }.isFailure()
                .given {
                    assertThat(it).isInstanceOf(CantusServiceException::class)
                }
        }
    }

    @Test
    fun `should throw when body us empty`() {
        server.execute(MockResponse().setResponseCode(HttpStatus.SC_NO_CONTENT)) {
            assertThat {
                service.getImageInformation(
                    "bar",
                    "foo",
                    "latest"
                )
            }.isFailure()
                .given {
                    assertThat(it).isInstanceOf(CantusServiceException::class)
                    assertThat(it.message).isNotNull().contains("cause=empty body")
                }
        }
    }
}

fun createImageTageResourceResponse(success: Boolean = true) = AuroraResponse(
    items = listOf(
        ImageTagResource(
            dockerDigest = "sha256:1234",
            appVersion = "0",
            auroraVersion = "0",
            dockerVersion = "0",
            requestUrl = "docker"
        )
    ),
    success = success
)
