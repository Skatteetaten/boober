package no.skatteetaten.aurora.boober.controller.v1

import org.junit.jupiter.api.Test
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.HttpHeaders
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import no.skatteetaten.aurora.boober.facade.DeleteApplicationDeploymentResponse
import no.skatteetaten.aurora.boober.facade.DeploymentFacade
import no.skatteetaten.aurora.boober.facade.GetApplicationDeploymentResponse
import no.skatteetaten.aurora.boober.model.ApplicationRef
import no.skatteetaten.aurora.mockmvc.extensions.Path
import no.skatteetaten.aurora.mockmvc.extensions.contentTypeJson
import no.skatteetaten.aurora.mockmvc.extensions.post
import no.skatteetaten.aurora.mockmvc.extensions.responseJsonPath
import no.skatteetaten.aurora.mockmvc.extensions.statusIsOk

@WebMvcTest(value = [ApplicationDeploymentController::class])
class ApplicationDeploymentControllerTest : AbstractControllerTest() {

    @MockkBean
    private lateinit var deploymentFacade: DeploymentFacade

    @Test
    fun `delete ApplicationRef`() {
        val applicationRef = ApplicationRef("paas-utv", "simple")
        val payload = ApplicationDeploymentPayload(listOf(applicationRef))

        every { deploymentFacade.executeDelete(payload.applicationRefs) } returns listOf(
            DeleteApplicationDeploymentResponse(applicationRef, true, "Application was successfully deleted.")
        )

        mockMvc.post(
            path = Path("/v1/applicationdeployment/delete"),
            headers = HttpHeaders().contentTypeJson(),
            body = payload
        ) {
            statusIsOk()
            responseJsonPath("$.success").isTrue()
            responseJsonPath("$.count").equalsValue(1)
            responseJsonPath("$.items.length()").equalsValue(1)
        }
    }

    @Test
    fun `list applicationRef given applicationDeploymentRef`() {
        val payload = ApplicationDeploymentRefPayload(listOf(adr))

        every { deploymentFacade.deploymentExist(auroraConfigRef, payload.adr) } returns listOf(
            GetApplicationDeploymentResponse(
                applicationRef = ApplicationRef("paas-utv", "simple"),
                success = true,
                exists = true,
                message = "Application exists"
            )
        )

        mockMvc.post(
            path = Path("/v1/applicationdeployment/{auroraConfig}", auroraConfigRef.name),
            headers = HttpHeaders().contentTypeJson(),
            body = payload
        ) {
            statusIsOk()
                .responseJsonPath("$.success").isTrue()
                .responseJsonPath("$.items[0].message").equalsValue("Application exists")
                .responseJsonPath("$.items[0].exists").equalsValue(true)
                .responseJsonPath("$.items[0].applicationRef").equalsObject(ApplicationRef("paas-utv", "simple"))
        }
    }
}
