package no.skatteetaten.aurora.boober.service.openshift

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.skatteetaten.aurora.boober.service.OpenShiftException
import no.skatteetaten.aurora.boober.utils.ResourceLoader
import no.skatteetaten.aurora.boober.utils.jsonMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatus.OK
import org.springframework.http.ResponseEntity
import org.springframework.web.client.HttpClientErrorException
import java.nio.charset.Charset

class OpenShiftClientTest : ResourceLoader() {

    @BeforeEach
    fun setup() {
        clearAllMocks()
    }

    val userClient = mockk<OpenShiftResourceClient>()

    val serviceAccountClient = mockk<OpenShiftResourceClient>()

    val mapper = ObjectMapper()

    val emptyNode = mapper.readValue<JsonNode>("{}")
    val openShiftClient = OpenShiftClient(userClient, serviceAccountClient, mapper)

    fun resourceClients() = listOf(
        ResourceClientData("rolebinding", userClient),
        ResourceClientData("route", serviceAccountClient),
        ResourceClientData("namespace", serviceAccountClient),
        ResourceClientData("service", userClient),
        ResourceClientData("deploymentconfig", userClient),
        ResourceClientData("imagestream", userClient)
    )

    data class ResourceClientData(
        val kind: String,
        val client: OpenShiftResourceClient
    )

    @ParameterizedTest
    @MethodSource("resourceClients")
    fun `Uses correct resource client based on OpenShift kind`(data: ResourceClientData) {

        val name = "does not matter"
        val mockedResource = """{ "kind": "${data.kind}", "metadata": { "name": "$name" } }"""
        val command = OpenshiftCommand(OperationType.CREATE, "http://foo/bar", mapper.readValue(mockedResource))
        val otherClient = if (data.client == serviceAccountClient) userClient else serviceAccountClient

        every {
            data.client.post(command.url, command.payload)
        } returns ResponseEntity(emptyNode, OK)

        openShiftClient.performOpenShiftCommand("aos", command)

        verify(exactly = 1) { data.client.post(any(), any()) }
        verify(exactly = 0) { otherClient.post(any(), any()) }
    }

    @Test
    fun `Creates OpenShiftGroup indexes`() {

        val response = loadResource("response_groups.json")
        val userResponse = loadResource("response_users.json")
        every {
            serviceAccountClient.get("/apis/user.openshift.io/v1/groups")
        } returns ResponseEntity(jsonMapper().readValue<JsonNode>(response), OK)

        every {
            serviceAccountClient.get("/apis/user.openshift.io/v1/users")
        } returns ResponseEntity(jsonMapper().readValue<JsonNode>(userResponse), OK)

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

        val cmd = OpenshiftCommand(OperationType.CREATE, "http://service.foo", payload)

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

        val cmd = OpenshiftCommand(OperationType.CREATE, "http://service.foo", payload)

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
