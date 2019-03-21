package no.skatteetaten.aurora.boober.service.openshift

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.skatteetaten.aurora.boober.utils.ResourceLoader
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

class OpenShiftClientTest : ResourceLoader() {

    val userClient = mockk<OpenShiftResourceClient>()

    val serviceAccountClient = mockk<OpenShiftResourceClient>()

    val mapper = ObjectMapper()

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
        openShiftClient.performOpenShiftCommand("aos", command)

        every {
            data.client.post(command.url, command.payload)
        } returns ResponseEntity(mapper.readValue("{}"), HttpStatus.OK)

        verify(exactly = 1) { data.client.post(any(), any()) }
        verify(exactly = 0) { otherClient.post(any(), any()) }
    }
    /*

    val "Creates OpenShiftGroup indexes"()
    {

        given:
        val response = loadResource("response_groups.json")
        val userResponse = loadResource("response_users.json")
        serviceAccountClient.get("/apis/user.openshift.io/v1/groups", _, _) > >
        ResponseEntity(ObjectMapper().readValue(response, JsonNode), OK)
        serviceAccountClient.get("/apis/user.openshift.io/v1/users", _, _) > >
        ResponseEntity(ObjectMapper().readValue(userResponse, JsonNode), OK)

        when:
        val openShiftGroups = openShiftClient.getGroups()

        then:
        openShiftGroups != null

        openShiftGroups.userGroups["k1111111"] == ["APP_PaaS_drift", "APP_PaaS_utv", "system:authenticated"]
        openShiftGroups.userGroups["k3222222"] == ["APP_PROJ1_drift", "system:authenticated"]
        openShiftGroups.groupUsers["APP_PaaS_drift"] == ["k2222222", "k1111111", "k3333333", "k4444444", "y5555555", "m6666666", "m7777777", "y8888888", "y9999999"]
        openShiftGroups.groupUsers["system:authenticated"] ==
            ["mTestUser", "k2222222", "k1111111", "k1222222", "k3333333", "k4444444", "k3222222", "k4222222", "k7111111", "y5555555", "y8888888", "y9999999", "m2111111", "m3111111", "m4111111", "m5111111", "m5222222", "m6222222", "y6222222", "m6111111", "m6666666", "m7777777", "m8111111", "x9111111"]
    }

    val "Should record exception when command fails"()
    {
        given:
        JsonNode payload = mapper . convertValue ([
            kind    : "service",
        metadata: [
        "name": "bar"
        ]
        ], JsonNode.class)

        val cmd = OpenshiftCommand(OperationType.CREATE, "", payload)
        userClient.post(_ as String, payload) > > {
            throw  OpenShiftException(
                "Does not exist",
                HttpClientErrorException(
                    HttpStatus.SERVICE_UNAVAILABLE, "not available",
                    """{ "failed" : "true"}""".bytes,
                    Charset.valaultCharset()
                )
            )
        }
        when:

        val result = openShiftClient.performOpenShiftCommand("foo", cmd)
        then:
        !result.success
        result.responseBody.get("failed").asText() == "true"
    }

    val "Should record exception when command fails with non json body"()
    {
        given:
        JsonNode payload = mapper . convertValue ([
            kind    : "service",
        metadata: [
        "name": "bar"
        ]
        ], JsonNode.class)

        val cmd = OpenshiftCommand(OperationType.CREATE, "", payload)
        userClient.post(_ as String, payload) > > {
            throw  OpenShiftException(
                "Does not exist",
                HttpClientErrorException(
                    HttpStatus.SERVICE_UNAVAILABLE, "not available", "failed".bytes,
                    Charset.valaultCharset()
                )
            )
        }
        when:

        val result = openShiftClient.performOpenShiftCommand("foo", cmd)
        then:
        !result.success
        result.responseBody.get("error").asText() == "failed"
    }
    */
}
