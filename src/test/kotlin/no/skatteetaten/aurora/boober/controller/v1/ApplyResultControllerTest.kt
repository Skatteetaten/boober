package no.skatteetaten.aurora.boober.controller.v1

import com.nhaarman.mockito_kotlin.any
import no.skatteetaten.aurora.boober.controller.internal.ErrorHandler
import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.service.DeployLogService
import no.skatteetaten.aurora.mockmvc.extensions.Path
import no.skatteetaten.aurora.mockmvc.extensions.get
import no.skatteetaten.aurora.mockmvc.extensions.mock.withContractResponse
import no.skatteetaten.aurora.mockmvc.extensions.mock.withNullableContractResponse
import no.skatteetaten.aurora.mockmvc.extensions.printResponseBody
import no.skatteetaten.aurora.mockmvc.extensions.responseJsonPath
import no.skatteetaten.aurora.mockmvc.extensions.status
import no.skatteetaten.aurora.mockmvc.extensions.statusIsOk
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.MockMvc
import org.springframework.web.client.HttpClientErrorException
import java.nio.charset.Charset

@AutoConfigureRestDocs
@WebMvcTest(controllers = [ApplyResultController::class, ErrorHandler::class], secure = false)
class ApplyResultControllerTest(@Autowired private val mockMvc: MockMvc) {

    @MockBean
    private lateinit var deployLogService: DeployLogService

    @Test
    fun `Get deploy history`() {
        val deployHistory =
            given(deployLogService.deployHistory(any())).withContractResponse("applyresult/deployhistory") {
                willReturn(content)
            }.mockResponse

        mockMvc.get(Path("/v1/apply-result/{auroraConfigName}/", "aos")) {
            statusIsOk().responseJsonPath("$").equalsObject(Response(items = deployHistory))
        }
    }

    @Test
    fun `Get deploy history by id`() {
        val deployResult = given(
            deployLogService.findDeployResultById(any(), any())
        ).withNullableContractResponse("applyresult/deployhistory") {
            willReturn(content)
        }.mockResponse

        mockMvc.get(Path("/v1/apply-result/aos/{deployId}", "123")) {
            statusIsOk().responseJsonPath("$").equalsObject(Response(item = deployResult))
        }
    }

    @Test
    fun `Get deploy history by id return not found when no DeployResult`() {
        mockMvc.get(Path("/v1/apply-result/aos/invalid-deployid")) {
            status(HttpStatus.NOT_FOUND)
        }
    }

    @Test
    fun `Get error response when findDeployResultById throws HttpClientErrorException`() {
        given(deployLogService.findDeployResultById(any(), any())).willThrow(
            HttpClientErrorException.create(
                HttpStatus.NOT_FOUND,
                "",
                HttpHeaders(),
                "404 ".toByteArray(),
                Charset.defaultCharset()
            )
        )

        mockMvc.get(Path("/v1/apply-result/aos/deployid")) {
            status(HttpStatus.INTERNAL_SERVER_ERROR)
            printResponseBody()
        }
    }
}