package no.skatteetaten.aurora.boober.service

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.utils.jsonMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.client.RestTemplate

class DockerServiceTest {

    val httpClient = mockk<RestTemplate>()
    val service = DockerService(httpClient)
    val mapper = jsonMapper()

    @BeforeEach
    fun setup() {
        clearAllMocks()
    }

    @Test
    fun `Should tag from one tag to another`() {
        val manifest: JsonNode = mapper.convertValue(mapOf("manifest" to "foo"))

        every {
            httpClient.exchange(any(), any() as Class<*>)
        } returns ResponseEntity(manifest, HttpStatus.OK) andThen ResponseEntity<JsonNode>(null, HttpStatus.CREATED)

        val response = service.tag(TagCommand("foo/bar", "1.2.3", "latest", "registry"))

        assertThat(response.success).isTrue()
    }

    @Test
    fun `Should not tag if we cannot find manifest`() {
        val manifest: JsonNode = mapper.convertValue(mapOf("manifest" to "foo"))

        every {
            httpClient.exchange(any(), any() as Class<*>)
        } returns ResponseEntity(manifest, HttpStatus.BAD_REQUEST)

        val response = service.tag(TagCommand("foo/bar", "1.2.3", "latest", "registry"))

        assertThat(response.success).isFalse()
    }
}
