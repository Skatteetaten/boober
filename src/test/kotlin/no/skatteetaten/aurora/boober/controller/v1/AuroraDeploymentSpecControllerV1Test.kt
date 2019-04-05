package no.skatteetaten.aurora.boober.controller.v1

import com.nhaarman.mockito_kotlin.any
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecService
import no.skatteetaten.aurora.mockmvc.extensions.Path
import no.skatteetaten.aurora.mockmvc.extensions.get
import no.skatteetaten.aurora.mockmvc.extensions.mock.withContractResponse
import no.skatteetaten.aurora.mockmvc.extensions.responseJsonPath
import no.skatteetaten.aurora.mockmvc.extensions.statusIsOk
import org.apache.commons.text.StringSubstitutor
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.web.servlet.MockMvc

@AutoConfigureRestDocs
@WebMvcTest(controllers = [AuroraDeploymentSpecControllerV1::class], secure = false)
class AuroraDeploymentSpecControllerV1Test(@Autowired private val mockMvc: MockMvc) {

    @MockBean
    private lateinit var auroraDeploymentSpecService: AuroraDeploymentSpecService

    @MockBean
    private lateinit var responder: AuroraDeploymentSpecResponder

    @Test
    fun `Return all deployment specs`() {
        given(auroraDeploymentSpecService.getAuroraDeploymentSpecs(any(), any())).willReturn(emptyList())

        val auroraDeploymentSpecs = given(responder.create(any<List<AuroraDeploymentSpec>>(), any()))
            .withContractResponse("auroradeploymentspec/deploymentspec") { willReturn(content) }.mockResponse

        mockMvc.get(Path("/v1/auroradeployspec/{name}/?adr=env/application", "auroraconfigname")) {
            statusIsOk()
                .responseJsonPath("$").equalsObject(auroraDeploymentSpecs)
        }
    }

    @Test
    fun `Return deployment spec for env and app`() {
        given(auroraDeploymentSpecService.getAuroraDeploymentSpec(any(), any(), any(), any()))
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
        given(auroraDeploymentSpecService.getAuroraDeploymentSpec(any(), any(), any(), any()))
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
        given(auroraDeploymentSpecService.getAuroraDeploymentSpecsForEnvironment(any(), any())).willReturn(emptyList())

        val auroraDeploymentSpec = given(responder.create(any<List<AuroraDeploymentSpec>>(), any()))
            .withContractResponse("auroradeploymentspec/deploymentspec") { willReturn(content) }.mockResponse

        mockMvc.get(Path("/v1/auroradeployspec/{name}/{env}/", "auroraconfigname", "environment")) {
            statusIsOk()
                .responseJsonPath("$").equalsObject(auroraDeploymentSpec)
        }
    }
}