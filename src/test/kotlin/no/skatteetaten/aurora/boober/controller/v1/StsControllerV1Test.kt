package no.skatteetaten.aurora.boober.controller.v1

import com.ninjasquad.springmockk.MockkBean
import no.skatteetaten.aurora.boober.facade.StsRenewFacade
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.web.servlet.MockMvc

@AutoConfigureRestDocs
@WebMvcTest(controllers = [StsControllerV1::class], secure = false)
class StsControllerV1Test(@Autowired private val mockMvc: MockMvc) {

    @MockkBean
    private lateinit var stsRenewFacade: StsRenewFacade

    /*
    TODO: fix
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

     */
}
