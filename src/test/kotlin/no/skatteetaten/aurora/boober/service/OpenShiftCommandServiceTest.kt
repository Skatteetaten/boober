package no.skatteetaten.aurora.boober.service

import assertk.assert
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.fasterxml.jackson.databind.JsonNode
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.utils.ResourceLoader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class OpenShiftCommandServiceTest : ResourceLoader() {

    private val namespace = "fragleberget"
    private val openshiftClient = mockk<OpenShiftClient>()
    private val generator = mockk<OpenShiftObjectGenerator>()

    private val service = OpenShiftCommandService(openshiftClient, generator)

    @AfterEach
    fun tearDown() {
        clearMocks(openshiftClient, generator)
    }

    @Test
    fun `Should accept successful command`() {
        val json = loadJsonResource("dc.json")
        createOpenShiftClientMock(json)
        val resultList = service.createAndApplyObjects(namespace, json, false)

        assert(resultList.size).isEqualTo(1)
        assert(resultList.first().success).isTrue()
    }

    @Test
    fun `Should fail ImageStreamImport where command succeeds but import has failed`() {
        val json = loadJsonResource("iis-failed.json")
        createOpenShiftClientMock(json)

        val resultList = service.createAndApplyObjects(namespace, json, false)
        assert(resultList.size).isEqualTo(1)
        val result = resultList.first()
        assert(result.success).isFalse()
        assert(result.exception)
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