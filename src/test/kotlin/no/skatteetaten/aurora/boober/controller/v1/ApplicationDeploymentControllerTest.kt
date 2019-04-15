package no.skatteetaten.aurora.boober.controller.v1

import com.nhaarman.mockito_kotlin.any
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.service.ApplicationDeploymentService
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import no.skatteetaten.aurora.boober.service.DeleteApplicationDeploymentResponse
import no.skatteetaten.aurora.mockmvc.extensions.Path
import no.skatteetaten.aurora.mockmvc.extensions.post
import no.skatteetaten.aurora.mockmvc.extensions.responseJsonPath
import no.skatteetaten.aurora.mockmvc.extensions.statusIsOk
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.web.servlet.MockMvc

@AutoConfigureRestDocs
@WebMvcTest(
    value = [ApplicationDeploymentController::class],
    secure = false
)
class ApplicationDeploymentControllerTest(@Autowired private val mockMvc: MockMvc) {

    @MockBean
    private lateinit var applicationDeploymentService: ApplicationDeploymentService

    @MockBean
    private lateinit var auroraConfigService: AuroraConfigService

    @Test
    fun `delete ApplicationDeployment given two ApplicationDeploymentRef return success`() {
        val path = "/v1/ApplicationDeploymentdelete"

        val applicationDeploymentRef = ApplicationDeploymentRef("demo", "test")
        val applicationDeploymentRefs = listOf(applicationDeploymentRef, applicationDeploymentRef)
        val deletePayload = DeletePayload(applicationDeploymentRefs)

        val applicationDeploymentDeleteResponse =
            DeleteApplicationDeploymentResponse(applicationDeploymentRef, true, "success")
        val applicationDeploymentDeleteResponses =
            listOf(applicationDeploymentDeleteResponse, applicationDeploymentDeleteResponse)

        // given(applicationDeploymentService.executeDelete(any())).willReturn(applicationDeploymentDeleteResponses)

        val deleteResponse =
            given(applicationDeploymentDeleteResponder.create(any()))
                .withContractResponse("applicationdeploymentdelete/success") { willReturn(content) }.mockResponse

        mockMvc.post(
            path = Path(path),
            body = deletePayload
        ) {
            statusIsOk()
                .responseJsonPath("$").equalsObject(deleteResponse)
        }
    }
}