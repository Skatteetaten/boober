package no.skatteetaten.aurora.boober.controller.v1

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import no.skatteetaten.aurora.boober.facade.AuroraConfigFacade
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.utils.stubAuroraDeploymentSpec
import no.skatteetaten.aurora.mockmvc.extensions.Path
import no.skatteetaten.aurora.mockmvc.extensions.get
import no.skatteetaten.aurora.mockmvc.extensions.responseJsonPath
import no.skatteetaten.aurora.mockmvc.extensions.statusIsOk
import org.junit.jupiter.api.Test
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest

// TODO: test overrides
@WebMvcTest(controllers = [AuroraDeploymentSpecControllerV1::class])
class AuroraDeploymentSpecControllerV1Test : AbstractControllerTest() {

    @MockkBean
    private lateinit var facade: AuroraConfigFacade

    val specs: List<AuroraDeploymentSpec> = listOf(stubAuroraDeploymentSpec())

    @Test
    fun `Return all deployment specs`() {

        every { facade.findAuroraDeploymentSpec(auroraConfigRef, listOf(adr)) } returns specs

        mockMvc.get(
            Path("/v1/auroradeployspec/{name}/?adr={env}/{app}", auroraConfigRef.name, adr.environment, adr.application)
        ) {
            statusIsOk()
            responseJsonPath("$.success").isTrue()
            responseJsonPath("$.items.length()").equalsValue(1)
            responseJsonPath("$.items[0].cluster.value").equalsValue("utv")
        }
    }

    @Test
    fun `Return deployment spec for env and app`() {

        every { facade.findAuroraDeploymentSpecSingle(auroraConfigRef, adr, emptyList()) } returns specs.first()

        mockMvc.get(
            Path(
                "/v1/auroradeployspec/{name}/{env}/{app}",
                auroraConfigRef.name,
                adr.environment,
                adr.application
            )
        ) {
            statusIsOk()
            responseJsonPath("$.success").isTrue()
            responseJsonPath("$.items.length()").equalsValue(1)
            responseJsonPath("$.items[0].cluster.value").equalsValue("utv")
        }
    }

    @Test
    fun `Return deployment spec for env`() {

        every { facade.findAuroraDeploymentSpecForEnvironment(auroraConfigRef, adr.environment) } returns specs

        mockMvc.get(Path("/v1/auroradeployspec/{name}/{env}/", auroraConfigRef.name, adr.environment)) {
            statusIsOk()
            responseJsonPath("$.success").isTrue()
            responseJsonPath("$.items.length()").equalsValue(1)
            responseJsonPath("$.items[0].cluster.value").equalsValue("utv")
        }
    }

    @Test
    fun `Return deployment spec for env and app formatted`() {

        every { facade.findAuroraDeploymentSpecSingle(auroraConfigRef, adr, emptyList()) } returns specs.first()

        mockMvc.get(
            Path(
                "/v1/auroradeployspec/{name}/{env}/{app}/formatted",
                auroraConfigRef.name, adr.environment, adr.application
            )
        ) {
            statusIsOk()
            responseJsonPath("$.success").isTrue()
            responseJsonPath("$.items.length()").equalsValue(1)
            responseJsonPath("$.items[0]").equalsValue(
                """     about.json | cluster: "utv"
     about.json | affiliation: "paas"
 utv/about.json | envName: "utv"
         static | applicationDeploymentId: "1234567890"
utv/simple.json | name: "simple""""
            )
        }
    }
}
