package no.skatteetaten.aurora.boober.service.openshift

import assertk.assertThat
import assertk.assertions.isTrue
import com.fasterxml.jackson.databind.JsonNode
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.getResultFiles
import no.skatteetaten.aurora.boober.service.OpenShiftCommandService
import no.skatteetaten.aurora.boober.utils.Instants
import no.skatteetaten.aurora.boober.utils.jsonMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import java.time.Instant

class OpenShiftCommandServiceCreateDeleteCommandsTest {

    val userClient: OpenShiftResourceClient = mockk()

    val openShiftClient = OpenShiftClient(userClient, mockk(), jsonMapper())
    val openShiftCommandBuilder = OpenShiftCommandService(openShiftClient, mockk())

    @BeforeEach
    fun setupTest() {

        clearAllMocks()
        every {
            userClient.getAuthorizationHeaders()
        } returns HttpHeaders()
        Instants.determineNow = { Instant.EPOCH }
    }

    @Test
    fun `Should create delete command for all resources with given deployId`() {

        val name = "aos-simple"
        val namespace = "booberdev"
        val deployId = "abc123"
        val aid = ApplicationDeploymentRef(namespace, name)

        val responses = createResponsesFromResultFiles(aid)

        responses.forEach {
            val kind = it.key
            val queryString = "labelSelector=app=$name,booberDeployId,booberDeployId!=$deployId"
            val apiUrl = OpenShiftResourceClient.generateUrl(kind, namespace)
            val url = "$apiUrl?$queryString"
            every {
                userClient.get(url, any(), true)
            } returns ResponseEntity.ok(it.value)
        }

        val commands = openShiftCommandBuilder.createOpenShiftDeleteCommands(name, namespace, deployId)

        listOf("BuildConfig", "DeploymentConfig", "ConfigMap", "ImageStream", "Service").forEach {
            assertThat(containsKind(it, commands)).isTrue()
        }
    }

    fun createResponsesFromResultFiles(aid: ApplicationDeploymentRef): Map<String, JsonNode> {

        return getResultFiles(aid).map {
            val responseBody = jsonMapper().createObjectNode()
            val items = jsonMapper().createArrayNode()

            val kind = it.key.split("/")[0]
            val kindList = it.value?.get("kind")?.textValue() + "List"

            items.add(it.value)
            responseBody.put("kind", kindList)
            responseBody.set("items", items)

            kind to responseBody
        }.toMap()
    }

    fun containsKind(kind: String, commands: List<OpenshiftCommand>): Boolean {
        return commands.any { cmd ->
            cmd.payload.get("kind")?.let {
                it.textValue() == kind
            } ?: false
        }
    }
}
