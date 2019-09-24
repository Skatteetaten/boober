package no.skatteetaten.aurora.boober.unit

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.fasterxml.jackson.databind.JsonNode
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import no.skatteetaten.aurora.boober.service.OpenShiftCommandService
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import no.skatteetaten.aurora.boober.utils.ResourceLoader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class OpenShiftCommandServiceTest : ResourceLoader() {

    private val namespace = "fragleberget"
    private val openshiftClient = mockk<OpenShiftClient>()

    private val service = OpenShiftCommandService(openshiftClient)

    @AfterEach
    fun tearDown() {
        clearMocks(openshiftClient)
    }

    @Test
    fun `Should parse error message from route`() {
        val json = loadJsonResource("route-failed.json")
        val response = OpenShiftResponse(
            command = OpenshiftCommand(OperationType.CREATE, ""),
            success = true,
            responseBody = json
        )
        assertThat(service.findErrorMessage(response)).isEqualTo("route ref3 already exposes foobar.paas-bjarte-dev.utv.paas.skead.no and is older")
    }

    @Test
    fun `Should accept successful command`() {
        val json = loadJsonResource("dc.json")
        createOpenShiftClientMock(json)
        val resultList = service.createAndApplyObjects(namespace, json, false)

        assertThat(resultList.size).isEqualTo(1)
        assertThat(resultList.first().success).isTrue()
    }

    @Test
    fun `Should fail ImageStreamImport where command succeeds but import has failed`() {
        val json = loadJsonResource("iis-failed.json")
        createOpenShiftClientMock(json)

        val resultList = service.createAndApplyObjects(namespace, json, false)
        assertThat(resultList.size).isEqualTo(1)
        val result = resultList.first()
        assertThat(result.success).isFalse()
        assertThat(result.exception)
            .isEqualTo("dockerimage.image.openshift.io \"docker-registry.aurora.sits.no:5000/no_skatteetaten_aurora_demo/whoami:foobar\" not found")
    }

    private fun createOpenShiftClientMock(json: JsonNode) {
        val commandSlot = slot<OpenshiftCommand>()
        every {
            openshiftClient.performOpenShiftCommand(
                namespace = namespace,
                command = capture(commandSlot)
            )
        } answers { OpenShiftResponse(commandSlot.captured, json) }
    }
}