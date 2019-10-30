package no.skatteetaten.aurora.boober.controller.v1

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.service.DeployLogService
import no.skatteetaten.aurora.boober.service.DeployLogServiceException
import no.skatteetaten.aurora.mockmvc.extensions.Path
import no.skatteetaten.aurora.mockmvc.extensions.get
import no.skatteetaten.aurora.mockmvc.extensions.responseJsonPath
import no.skatteetaten.aurora.mockmvc.extensions.status
import no.skatteetaten.aurora.mockmvc.extensions.statusIsOk
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
            deployLogService.deployHistory(auroraConfigRef)
        } returns items

        mockMvc.get(Path("/v1/apply-result/{auroraConfigName}", auroraConfigRef.name)) {
            statusIsOk().responseJsonPath("$").equalsObject(Response(items = items))
        }
    }

    @Test
    fun `Get deploy history by id`() {

        val deployId = "123"
        every {
            deployLogService.findDeployResultById(auroraConfigRef, deployId)
        } returns items.first()

        mockMvc.get(Path("/v1/apply-result/{auroraConfigName}/{deployId}", auroraConfigRef.name, deployId)) {
            statusIsOk().responseJsonPath("$").equalsObject(Response(item = items.first()))
        }
    }

    @Test
    fun `Get deploy history by id return not found when no DeployResult`() {
        val deployId = "invalid-id"
        every {
            deployLogService.findDeployResultById(auroraConfigRef, deployId)
        } returns null

        mockMvc.get(
            Path("/v1/apply-result/{auroraConfigName}/{deployId}", auroraConfigRef.name, deployId),
            docsIdentifier = "get-v1-apply-result-auroraConfigName-deployId-failure"
        ) {
            status(HttpStatus.NOT_FOUND)
        }
    }

    @Test
    fun `Get error response when findDeployResultById throws HttpClientErrorException`() {

        val deployId = "1235"
        every {
            deployLogService.findDeployResultById(auroraConfigRef, deployId)
        } throws DeployLogServiceException("DeployId $deployId was not found for affiliation ${auroraConfigRef.name}")

        mockMvc.get(
            Path("/v1/apply-result/{auroraConfigName}/{deployId}", auroraConfigRef.name, deployId),
            docsIdentifier = "get-v1-apply-result-auroraConfigName-deployId-failure-not-found-http"
        ) {
            status(HttpStatus.INTERNAL_SERVER_ERROR)
                .responseJsonPath("$.message").contains(deployId)
                .responseJsonPath("$.message").contains(auroraConfigRef.name)
        }
    }
}
