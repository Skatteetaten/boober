package no.skatteetaten.aurora.boober.unit

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.skatteetaten.aurora.boober.service.OpenShiftException
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import no.skatteetaten.aurora.boober.utils.ResourceLoader
import no.skatteetaten.aurora.boober.utils.jsonMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatus.OK
import org.springframework.http.ResponseEntity
import org.springframework.web.client.HttpClientErrorException
import java.nio.charset.Charset

private val userClient = mockk<OpenShiftResourceClient>()
private val serviceAccountClient = mockk<OpenShiftResourceClient>()

class OpenShiftClientTest : ResourceLoader() {

    @BeforeEach
    fun setup() {
        clearAllMocks()
    }

    val emptyNode = mapper.readValue<JsonNode>("{}")
    val openShiftClient = OpenShiftClient(
        userClient,
        serviceAccountClient,
        mapper
    )

    data class ResourceClientData(
        val kind: String,
        val client: OpenShiftResourceClient
    )

    enum class ResourceClients(val data: ResourceClientData) {
        ROLEBINDING(
            ResourceClientData(
                "rolebinding",
                serviceAccountClient
            )
        ),
        ROUTE(
            ResourceClientData(
                "route",
                serviceAccountClient
            )
        ),
        NAMESPACE(
            ResourceClientData(
                "namespace",
                serviceAccountClient
            )
        ),
        SERVICE(
            ResourceClientData(
                "service",
                userClient
            )
        ),
        DEPLOYMENTCONFIG(
            ResourceClientData(
                "deploymentconfig",
                userClient
            )
        ),
        IMAGESTREAM(
            ResourceClientData(
                "imagestream",
                userClient
            )
        )
    }

    @ParameterizedTest
    @EnumSource(ResourceClients::class)
    fun `Uses correct resource client based on OpenShift kind`(client: ResourceClients) {

        val name = "does not matter"
        val mockedResource = """{ "kind": "${client.data.kind}", "metadata": { "name": "$name" } }"""
        val command = OpenshiftCommand(
            OperationType.CREATE,
            "http://foo/bar",
            mapper.readValue(mockedResource)
        )
        val otherClient = if (client.data.client == serviceAccountClient) userClient else serviceAccountClient

        every {
            client.data.client.post(command.url, command.payload)
        } returns ResponseEntity(emptyNode, OK)

        openShiftClient.performOpenShiftCommand("aos", command)

        verify(exactly = 1) { client.data.client.post(any(), any()) }
        verify(exactly = 0) { otherClient.post(any(), any()) }
    }

    @Test
    fun `Should record exception when command fails`() {
        val jsonMap = mapOf(
            "kind" to "service",
            "metadata" to mapOf("name" to "bar")
        )

        val payload: JsonNode = jsonMapper().convertValue(jsonMap)

        val cmd = OpenshiftCommand(
            OperationType.CREATE,
            "http://service.foo",
            payload
        )

        every { userClient.post(cmd.url, payload) } throws OpenShiftException(
            "Does not exist",
            HttpClientErrorException(
                HttpStatus.SERVICE_UNAVAILABLE, "not available",
                """{ "failed" : "true"}""".toByteArray(),
                Charset.defaultCharset()
            )
        )

        val result = openShiftClient.performOpenShiftCommand("foo", cmd)

        assertThat(result.success).isFalse()
        assertThat(result.responseBody?.get("failed")?.asText()).isEqualTo("true")
    }

    @Test
    fun `Should record exception when command fails with string body`() {
        val jsonMap = mapOf(
            "kind" to "service",
            "metadata" to mapOf("name" to "bar")
        )

        val payload: JsonNode = jsonMapper().convertValue(jsonMap)

        val cmd = OpenshiftCommand(
            OperationType.CREATE,
            "http://service.foo",
            payload
        )

        every { userClient.post(cmd.url, payload) } throws OpenShiftException(
            "Does not exist",
            HttpClientErrorException(
                HttpStatus.SERVICE_UNAVAILABLE, "not available",
                """failed""".toByteArray(),
                Charset.defaultCharset()
            )
        )

        val result = openShiftClient.performOpenShiftCommand("foo", cmd)

        assertThat(result.success).isFalse()
        assertThat(result.responseBody?.get("error")?.asText()).isEqualTo("failed")
    }
}
