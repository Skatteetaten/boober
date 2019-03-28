package no.skatteetaten.aurora.boober.controller.v1

import com.fasterxml.jackson.databind.node.NullNode
import com.nhaarman.mockito_kotlin.any
import no.skatteetaten.aurora.boober.controller.Responder
import no.skatteetaten.aurora.boober.service.DeployLogService
import no.skatteetaten.aurora.mockmvc.extensions.Path
import no.skatteetaten.aurora.mockmvc.extensions.get
import no.skatteetaten.aurora.mockmvc.extensions.mock.withContractResponse
import no.skatteetaten.aurora.mockmvc.extensions.responseJsonPath
import no.skatteetaten.aurora.mockmvc.extensions.status
import no.skatteetaten.aurora.mockmvc.extensions.statusIsOk
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.MockMvc

@AutoConfigureRestDocs
@WebMvcTest(controllers = [ApplyResultController::class], secure = false)
class ApplyResultControllerTest(@Autowired private val mockMvc: MockMvc) {

    @MockBean
    private lateinit var deployLogService: DeployLogService

    @MockBean
    private lateinit var responder: Responder

    @Test
    fun `Get deploy history`() {
        val deployHistory = given(responder.create(any())).withContractResponse("applyresult/deployhistory") {
            willReturn(content)
        }.mockResponse

        mockMvc.get(Path("/v1/apply-result/{auroraConfigName}/", "aos")) {
            statusIsOk().responseJsonPath("$").equalsObject(deployHistory)
        }
    }

    @Test
    fun `Get deploy history by id`() {
        given(deployLogService.findDeployResultById(any(), any())).willReturn(NullNode.instance)
        val deployHistory =
            given(responder.create(any<Any>())).withContractResponse("applyresult/deployhistory") {
                willReturn(content)
            }.mockResponse

        mockMvc.get(Path("/v1/apply-result/aos/{deployId}", "123")) {
            statusIsOk().responseJsonPath("$").equalsObject(deployHistory)
        }
    }

    @Test
    fun `Get deploy history by id return not found when no DeployResult`() {
        mockMvc.get(Path("/v1/apply-result/aos/invalid-deployid")) {
            status(HttpStatus.NOT_FOUND)
        }
    }
}