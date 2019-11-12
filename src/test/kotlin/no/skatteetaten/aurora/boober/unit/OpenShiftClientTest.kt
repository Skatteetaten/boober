package no.skatteetaten.aurora.boober.unit

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
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
                userClient
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
    fun `Creates OpenShiftGroup indexes`() {

        val response = loadJsonResource("response_groups.json")
        val userResponse = loadJsonResource("response_users.json")
        every {
            serviceAccountClient.get("/apis/user.openshift.io/v1/groups")
        } returns ResponseEntity(response, OK)

        every {
            serviceAccountClient.get("/apis/user.openshift.io/v1/users")
        } returns ResponseEntity(userResponse, OK)

        val openShiftGroups = openShiftClient.getGroups()

        assertThat(openShiftGroups).isNotNull()
        assertThat(openShiftGroups.getGroupsForUser("k1111111")).isEqualTo(
            listOf("APP_PaaS_drift", "APP_PaaS_utv", "system:authenticated")
        )
        assertThat(openShiftGroups.getGroupsForUser("k3222222")).isEqualTo(
            listOf("APP_PROJ1_drift", "system:authenticated")
        )

        assertThat(
            openShiftGroups.getUsersForGroup("APP_PaaS_drift")
        ).isEqualTo(
            listOf(
                "k2222222",
                "k1111111",
                "k3333333",
                "k4444444",
                "y5555555",
                "m6666666",
                "m7777777",
                "y8888888",
                "y9999999"
            )
        )
        assertThat(openShiftGroups.getUsersForGroup("system:authenticated")).isEqualTo(
            listOf(
                "mTestUser",
                "k2222222",
                "k1111111",
                "k1222222",
                "k3333333",
                "k4444444",
                "k3222222",
                "k4222222",
                "k7111111",
                "y5555555",
                "y8888888",
                "y9999999",
                "m2111111",
                "m3111111",
                "m4111111",
                "m5111111",
                "m5222222",
                "m6222222",
                "y6222222",
                "m6111111",
                "m6666666",
                "m7777777",
                "m8111111",
                "x9111111"
            )
        )
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
