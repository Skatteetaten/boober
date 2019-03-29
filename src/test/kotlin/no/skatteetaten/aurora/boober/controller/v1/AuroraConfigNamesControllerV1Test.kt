package no.skatteetaten.aurora.boober.controller.v1

import com.nhaarman.mockito_kotlin.any
import no.skatteetaten.aurora.boober.controller.Responder
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

@AutoConfigureRestDocs
@WebMvcTest(controllers = [AuroraConfigNamesControllerV1::class], secure = false)
class AuroraConfigNamesControllerV1Test(@Autowired private val mockMvc: MockMvc) {

    @MockBean
    private lateinit var auroraConfigService: AuroraConfigService

    @MockBean
    private lateinit var responder: Responder

    @Test
    fun `Return aurora config names`() {
        given(auroraConfigService.findAllAuroraConfigNames()).willReturn(listOf("test123"))
        val auroraConfigNames = given(responder.create(any<List<String>>()))
            .withContractResponse("auroraconfignames/auroraconfignames") {
                willReturn(content)
            }.mockResponse

        mockMvc.get(Path("/v1/auroraconfignames")) {
            statusIsOk().responseJsonPath("$").equalsObject(auroraConfigNames)
        }
    }
}