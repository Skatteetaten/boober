package no.skatteetaten.aurora.boober.controller.v2

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import no.skatteetaten.aurora.boober.controller.v1.AbstractControllerTest
import no.skatteetaten.aurora.boober.facade.AuroraConfigFacade
import no.skatteetaten.aurora.boober.utils.stubAuroraDeploymentSpec
import no.skatteetaten.aurora.mockmvc.extensions.Path
import no.skatteetaten.aurora.mockmvc.extensions.get
import no.skatteetaten.aurora.mockmvc.extensions.printResponseBody
import no.skatteetaten.aurora.mockmvc.extensions.responseJsonPath
import no.skatteetaten.aurora.mockmvc.extensions.statusIsOk
import org.junit.jupiter.api.Test
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest

@WebMvcTest(controllers = [MultiAffiliationControllerV2::class])
class MultiAffiliationControllerV2Test : AbstractControllerTest() {

    @MockkBean
    private lateinit var facade: AuroraConfigFacade

    @Test
    fun `Search for environment name`() {

        every {
            facade.findAllAuroraConfigNames()
        } returns arrayListOf("fk1-utv", "fk1-s")

        every {
            facade.findAllApplicationDeploymentSpecs(any(), any())
        } returns listOf(stubAuroraDeploymentSpec())

        mockMvc.get(path = Path("/v2/multiaffiliation/{environment}", "fk1-utv")) {
            printResponseBody()
            statusIsOk().responseJsonPath("$.success").isTrue()
            statusIsOk().responseJsonPath("$.count").equalsValue(2)
        }
    }
}
