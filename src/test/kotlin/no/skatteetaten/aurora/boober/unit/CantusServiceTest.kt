package no.skatteetaten.aurora.boober.unit

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.client.RestTemplate
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.service.CantusRestTemplateWrapper
import no.skatteetaten.aurora.boober.service.CantusService
import no.skatteetaten.aurora.boober.service.TagCommand
import no.skatteetaten.aurora.boober.utils.jsonMapper

class CantusServiceTest {

    val httpClient = mockk<RestTemplate>()
    val service = CantusService(CantusRestTemplateWrapper(httpClient), "http://docker.com")
    val mapper = jsonMapper()

    @BeforeEach
    fun setup() {
        clearAllMocks()
    }

    @Test
    fun `Should tag from one tag to another`() {
        val manifest: JsonNode = mapper.convertValue(mapOf("manifest" to "foo"))

        every {
            httpClient.exchange(any(), any(), any(), any() as Class<*>, any() as Map<String, *>)
        } returns ResponseEntity(manifest, HttpStatus.OK)

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

    @Test
    fun `Should record failure`() {
        val manifest: JsonNode = mapper.convertValue(mapOf("manifest" to "foo"))

        every {
            httpClient.exchange(any(), any(), any(), any() as Class<*>, any() as Map<String, *>)
        } returns ResponseEntity(manifest, HttpStatus.BAD_GATEWAY)

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
