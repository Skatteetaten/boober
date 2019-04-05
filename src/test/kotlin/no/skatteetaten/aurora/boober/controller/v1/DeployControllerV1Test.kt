package no.skatteetaten.aurora.boober.controller.v1

import com.nhaarman.mockito_kotlin.any
import no.skatteetaten.aurora.boober.controller.internal.ApplyPayload
import no.skatteetaten.aurora.boober.service.DeployService
import no.skatteetaten.aurora.mockmvc.extensions.Path
import no.skatteetaten.aurora.mockmvc.extensions.contentTypeJson
import no.skatteetaten.aurora.mockmvc.extensions.mock.withContractResponse
import no.skatteetaten.aurora.mockmvc.extensions.put
import no.skatteetaten.aurora.mockmvc.extensions.responseJsonPath
import no.skatteetaten.aurora.mockmvc.extensions.statusIsOk
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc

@AutoConfigureRestDocs
@WebMvcTest(controllers = [DeployControllerV1::class], secure = false)
class DeployControllerV1Test(@Autowired private val mockMvc: MockMvc) {

    @MockBean
    private lateinit var deployService: DeployService

    @MockBean
    private lateinit var responder: DeployResponder

    @Test
    fun `Execute deploy`() {
        given(deployService.executeDeploy(any(), any(), any(), any())).willReturn(emptyList())

        val response = given(responder.create(any()))
            .withContractResponse("deploy/deploy") { willReturn(content) }.mockResponse

        mockMvc.put(
            path = Path("/v1/apply/{affiliation}", "paas"),
            headers = HttpHeaders().contentTypeJson(),
            body = ApplyPayload()
        ) {
            statusIsOk()
                .responseJsonPath("$").equalsObject(response)
        }
    }
}