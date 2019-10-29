package no.skatteetaten.aurora.boober.controller.v1

import no.skatteetaten.aurora.boober.facade.DeployFacade
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.web.servlet.MockMvc

@AutoConfigureRestDocs
@WebMvcTest(controllers = [DeployControllerV1::class], secure = false)
class DeployControllerV1Test(@Autowired private val mockMvc: MockMvc) {

    @MockBean
    private lateinit var deployFacade: DeployFacade

    /*
    TODO: Fix
    @Test
    fun `Execute deploy`() {
        given(deployService.executeDeploy(any(), any(), any(), any())).willReturn(emptyList())

        val deployResponses: List<DeployControllerV1.DeployResponse> = any()
        val response = given(deployResponses.find { !it.success }
            ?.let { Response(items = deployResponses, success = false, message = it.reason ?: "Deploy failed") }
            ?: Response(items = deployResponses))
            .withContractResponse("deploy/deploy") { willReturn(content) }.mockResponse

        mockMvc.put(
            path = Path("/v1/apply/{affiliation}", "paas"),
            headers = HttpHeaders().contentTypeJson(),
            body = ApplyPayload()
        ) {
            statusIsOk()
                .responseJsonPath("$").equalsObject(response)
        }
    }
     */
}
