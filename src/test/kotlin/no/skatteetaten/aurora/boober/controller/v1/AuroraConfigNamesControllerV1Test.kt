package no.skatteetaten.aurora.boober.controller.v1

import no.skatteetaten.aurora.boober.service.AuroraConfigService
import no.skatteetaten.aurora.mockmvc.extensions.Path
import no.skatteetaten.aurora.mockmvc.extensions.get
import no.skatteetaten.aurora.mockmvc.extensions.mock.withContractResponse
import no.skatteetaten.aurora.mockmvc.extensions.responseJsonPath
import no.skatteetaten.aurora.mockmvc.extensions.statusIsOk
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.web.servlet.MockMvc

/*
@AutoConfigureRestDocs
@WebMvcTest(controllers = [AuroraConfigNamesControllerV1::class], secure = false)
class AuroraConfigNamesControllerV1Test(@Autowired private val mockMvc: MockMvc) {

    @MockBean
    private lateinit var auroraConfigService: AuroraConfigService

    @Test
    fun `Return aurora config names`() {
        given(auroraConfigService.findAllAuroraConfigNames())
            .withContractResponse("auroraconfignames/auroraconfignames") { willReturn(content) }

        mockMvc.get(Path("/v1/auroraconfignames")) {
            statusIsOk()
                .responseJsonPath("$.success").isTrue()
                .responseJsonPath("$.items.length()").equalsValue(4)
        }
    }
}

 */
