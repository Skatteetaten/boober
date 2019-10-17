package no.skatteetaten.aurora.boober.controller.v1

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.web.servlet.MockMvc

// TODO: Fix
@AutoConfigureRestDocs
@WebMvcTest(controllers = [AuroraDeploymentSpecControllerV1::class], secure = false)
class AuroraDeploymentSpecControllerV1Test(@Autowired private val mockMvc: MockMvc) {

    /*
    @MockBean
    private lateinit var auroraDeploymentContextService: AuroraDeploymentContextService

    @MockBean
    private lateinit var responder: AuroraDeploymentContextResponder

    @Test
    fun `Return all deployment specs`() {
        given(auroraDeploymentContextService.getAuroraDeploymentSpecs(any(), any())).willReturn(emptyList())

        val auroraDeploymentSpecs = given(responder.create(any<List<AuroraDeploymentSpec>>(), any()))
            .withContractResponse("auroradeploymentspec/deploymentspec") { willReturn(content) }.mockResponse

        mockMvc.get(Path("/v1/auroradeployspec/{name}/?adr=env/application", "auroraconfigname")) {
            statusIsOk()
                .responseJsonPath("$").equalsObject(auroraDeploymentSpecs)
        }
    }

    @Test
    fun `Return deployment spec for env and app`() {
        given(auroraDeploymentContextService.getAuroraDeploymentContext(any(), any(), any(), any()))
            .willReturn(AuroraDeploymentSpec(emptyMap(), StringSubstitutor()))

        val auroraDeploymentSpec = given(responder.create(any<AuroraDeploymentSpec>(), any()))
            .withContractResponse("auroradeploymentspec/deploymentspec") { willReturn(content) }.mockResponse

        mockMvc.get(Path("/v1/auroradeployspec/{name}/{env}/{app}", "auroraconfigname", "utv", "application")) {
            statusIsOk()
                .responseJsonPath("$").equalsObject(auroraDeploymentSpec)
        }
    }

    @Test
    fun `Return deployment spec for env and app formatted`() {
        given(auroraDeploymentContextService.getAuroraDeploymentContext(any(), any(), any(), any()))
            .willReturn(AuroraDeploymentSpec(emptyMap(), StringSubstitutor()))

        val auroraDeploymentSpecFormatted = given(responder.create(any()))
            .withContractResponse("auroradeploymentspec/deploymentspec-formatted") { willReturn(content) }
            .mockResponse

        mockMvc.get(
            Path(
                "/v1/auroradeployspec/{name}/{env}/{app}/formatted",
                "auroraconfigname",
                "utv",
                "application"
            )
        ) {
            statusIsOk()
                .responseJsonPath("$").equalsObject(auroraDeploymentSpecFormatted)
        }
    }

    @Test
    fun `Return deployment specs for env`() {
        given(auroraDeploymentContextService.getAuroraDeploymentSpecsForEnvironment(any(), any())).willReturn(emptyList())

        val auroraDeploymentSpec = given(responder.create(any<List<AuroraDeploymentSpec>>(), any()))
            .withContractResponse("auroradeploymentspec/deploymentspec") { willReturn(content) }.mockResponse

        mockMvc.get(Path("/v1/auroradeployspec/{name}/{env}/", "auroraconfigname", "environment")) {
            statusIsOk()
                .responseJsonPath("$").equalsObject(auroraDeploymentSpec)
        }
    }
     */
}
