package no.skatteetaten.aurora.boober.controller.v1

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import no.skatteetaten.aurora.boober.facade.AuroraConfigFacade
import no.skatteetaten.aurora.mockmvc.extensions.Path
import no.skatteetaten.aurora.mockmvc.extensions.get
import no.skatteetaten.aurora.mockmvc.extensions.responseJsonPath
import no.skatteetaten.aurora.mockmvc.extensions.statusIsOk
import org.junit.jupiter.api.Test
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest

@WebMvcTest(controllers = [AuroraConfigNamesControllerV1::class])
class AuroraConfigNamesControllerV1Test : AbstractControllerTest() {

    @MockkBean
    private lateinit var facade: AuroraConfigFacade

    @Test
    fun `Return aurora config names`() {
        every {
            facade.findAllAuroraConfigNames()
        } returns listOf("paas")

        mockMvc.get(Path("/v1/auroraconfignames")) {
            statusIsOk()
                .responseJsonPath("$.success").isTrue()
                .responseJsonPath("$.items.length()").equalsValue(1)
        }
    }
}

