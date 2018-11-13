package no.skatteetaten.aurora.boober.service

import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
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
    fun `Should accept successfull command`() {
        val json = loadJsonResource("dc.json")
        val commandSlot = slot<OpenshiftCommand>()
        every {
            openshiftClient.performOpenShiftCommand(
                namespace = namespace,
                command = capture(commandSlot)
            )
        } answers { OpenShiftResponse(commandSlot.captured, json) }
        val resultList = service.createAndApplyObjects(namespace, json, false)

        assertk.assert(resultList.size).isEqualTo(1)
        val result = resultList.first()
        assertk.assert(result.success).isTrue()
    }

    @Test
    fun `Should fail ImageStreamImport where command succeeds but import has failed`() {

        val json = loadJsonResource("iis-failed.json")
        val commandSlot = slot<OpenshiftCommand>()
        every {
            openshiftClient.performOpenShiftCommand(
                namespace = namespace,
                command = capture(commandSlot)
            )
        } answers { OpenShiftResponse(commandSlot.captured, json) }

        val resultList = service.createAndApplyObjects(namespace, json, false)
        assertk.assert(resultList.size).isEqualTo(1)
        val result = resultList.first()
        assertk.assert(result.success).isFalse()
        assertk.assert(result.exception)
            .isEqualTo("dockerimage.image.openshift.io \"docker-registry.aurora.sits.no:5000/no_skatteetaten_aurora_demo/whoami:foobar\" not found")
    }
}