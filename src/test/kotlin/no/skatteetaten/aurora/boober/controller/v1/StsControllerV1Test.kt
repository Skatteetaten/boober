package no.skatteetaten.aurora.boober.controller.v1

import com.nhaarman.mockito_kotlin.any
import io.fabric8.kubernetes.api.model.OwnerReference
import no.skatteetaten.aurora.boober.service.RenewRequest
import no.skatteetaten.aurora.boober.service.StsRenewService
import no.skatteetaten.aurora.mockmvc.extensions.Path
import no.skatteetaten.aurora.mockmvc.extensions.contentTypeJson
import no.skatteetaten.aurora.mockmvc.extensions.mock.withContractResponse
import no.skatteetaten.aurora.mockmvc.extensions.post
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
@WebMvcTest(controllers = [StsControllerV1::class], secure = false)
class StsControllerV1Test(@Autowired private val mockMvc: MockMvc) {

    @MockBean
    private lateinit var stsRenewService: StsRenewService

    @MockBean
    private lateinit var stsResponder: StsResponder

    @Test
    fun `Renew certificates`() {
        given(stsRenewService.renew(any())).willReturn(emptyList())

        val openShiftResponses = given(stsResponder.create(any(), any()))
            .withContractResponse("sts/openShiftResponses") { willReturn(content) }.mockResponse

        val renewRequest = RenewRequest(
            name = "name",
            namespace = "namespace",
            affiliation = "affiliation",
            commonName = "commonName",
            ownerReference = OwnerReference()
        )

        mockMvc.post(
            path = Path("/v1/sts"),
            headers = HttpHeaders().contentTypeJson(),
            body = renewRequest
        ) {
            statusIsOk().responseJsonPath("$").equalsObject(openShiftResponses)
        }
    }
}