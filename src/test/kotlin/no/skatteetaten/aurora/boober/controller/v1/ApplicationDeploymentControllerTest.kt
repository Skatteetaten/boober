package no.skatteetaten.aurora.boober.controller.v1

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import no.skatteetaten.aurora.boober.facade.DeleteApplicationDeploymentResponse
import no.skatteetaten.aurora.boober.facade.DeploymentFacade
import no.skatteetaten.aurora.boober.model.ApplicationRef
import no.skatteetaten.aurora.mockmvc.extensions.Path
import no.skatteetaten.aurora.mockmvc.extensions.contentTypeJson
import no.skatteetaten.aurora.mockmvc.extensions.post
import no.skatteetaten.aurora.mockmvc.extensions.responseJsonPath
import no.skatteetaten.aurora.mockmvc.extensions.statusIsOk
import org.junit.jupiter.api.Test
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.HttpHeaders

@WebMvcTest(value = [ApplicationDeploymentController::class, DeploymentFacade::class])
class ApplicationDeploymentControllerTest : AbstractControllerTest() {

    @MockkBean
    private lateinit var deploymentFacade: DeploymentFacade

    @Test
    fun `delete ApplicationRef`() {
        val applicationRef = ApplicationRef("demo-deploy", "test")
        val payload = ApplicationDeploymentPayload(listOf(applicationRef))

        every { deploymentFacade.executeDelete(any()) } returns listOf(
            DeleteApplicationDeploymentResponse(ApplicationRef("namespace", "name"), true, "")
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

    /* TODO: Fix
    @Test
    fun `list applicationRef given applicationDeploymentRef`() {
        val adr = ApplicationDeploymentRef("deploy", "reference")
        val payload = ApplicationDeploymentRefPayload(listOf(adr))

        val applicationRef = given(auroraDeploymentContextService.expandDeploymentRefToApplicationRef(any(), any(), any()))
                .withContractResponse("applicationdeployment/applications") {
                    willReturn(content)
                }.mockResponse

        given(applicationDeploymentService.checkApplicationDeploymentsExists(applicationRef))
                .withContractResponse("applicationdeployment/applications_status") {
                    willReturn(content)
                }

        mockMvc.post(
                path = Path("/v1/applicationdeployment/demo?reference=test"),
                headers = HttpHeaders().contentTypeJson(),
                body = payload
        ) {
            statusIsOk()
                    .responseJsonPath("$.success").isTrue()
                    .responseJsonPath("$.items[0].message").equalsValue("Application exists")
                    .responseJsonPath("$.items[0].exists").equalsValue(true)
                    .responseJsonPath("$.items[0].applicationRef").equalsObject(ApplicationRef("demo-deploy", "reference"))
        }
    } */
}

