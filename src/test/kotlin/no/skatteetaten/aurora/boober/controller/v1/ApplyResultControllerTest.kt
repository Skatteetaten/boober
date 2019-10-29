package no.skatteetaten.aurora.boober.controller.v1

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.DeployLogService
import no.skatteetaten.aurora.boober.service.DeployLogServiceException
import no.skatteetaten.aurora.mockmvc.extensions.*
import org.junit.jupiter.api.Test
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.HttpStatus

@WebMvcTest(value = [ApplyResultController::class])
class ApplyResultControllerTest : AbstractControllerTest() {

    @MockkBean
    private lateinit var deployLogService: DeployLogService

    val items = listOf(loadJsonResource("deployhistory.json"))

    @Test
    fun `Get deploy history`() {

        every {
            deployLogService.deployHistory(AuroraConfigRef("aos", "master"))
        } returns items


        mockMvc.get(Path("/v1/apply-result/{auroraConfigName}/", "aos")) {
            statusIsOk().responseJsonPath("$").equalsObject(Response(items = items))
        }
    }

    @Test
    fun `Get deploy history by id`() {

        every {
            deployLogService.findDeployResultById(any(), any())
        } returns items.first()


        mockMvc.get(Path("/v1/apply-result/aos/{deployId}", "123")) {
            statusIsOk().responseJsonPath("$").equalsObject(Response(item = items.first()))
        }
    }

    @Test
    fun `Get deploy history by id return not found when no DeployResult`() {
        every {
            deployLogService.findDeployResultById(any(), any())
        } returns null

        mockMvc.get(Path("/v1/apply-result/aos/invalid-id")) {
            status(HttpStatus.NOT_FOUND)
        }
    }

    @Test
    fun `Get error response when findDeployResultById throws HttpClientErrorException`() {

        every {
            deployLogService.findDeployResultById(any(), any())
        } throws DeployLogServiceException("DeployId abc123 was not found for affiliation aos")


        mockMvc.get(Path("/v1/apply-result/aos/abc123")) {
            status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .responseJsonPath("$.message").contains("abc123")
                    .responseJsonPath("$.message").contains("aos")
        }
    }
}
