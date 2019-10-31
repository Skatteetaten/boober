package no.skatteetaten.aurora.boober.controller.v1

import com.ninjasquad.springmockk.MockkBean
import io.fabric8.kubernetes.api.model.OwnerReference
import io.mockk.every
import no.skatteetaten.aurora.boober.facade.RenewRequest
import no.skatteetaten.aurora.boober.facade.StsRenewFacade
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import no.skatteetaten.aurora.mockmvc.extensions.Path
import no.skatteetaten.aurora.mockmvc.extensions.contentTypeJson
import no.skatteetaten.aurora.mockmvc.extensions.post
import no.skatteetaten.aurora.mockmvc.extensions.responseJsonPath
import no.skatteetaten.aurora.mockmvc.extensions.statusIsOk
import org.junit.jupiter.api.Test
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.HttpHeaders

@WebMvcTest(controllers = [StsControllerV1::class])
class StsControllerV1Test : AbstractControllerTest() {

    @MockkBean
    private lateinit var stsRenewFacade: StsRenewFacade

    @Test
    fun `Renew certificates`() {

        val responses = listOf(
            OpenShiftResponse(
                success = true,
                command = OpenshiftCommand(
                    operationType = OperationType.UPDATE,
                    url = "/update"
                )
            )
        )

        val renewRequest = RenewRequest(
            name = "simple",
            namespace = "paas-utv",
            affiliation = "paas",
            commonName = "org.test.simple",
            ownerReference = OwnerReference()
        )

        every {
            stsRenewFacade.renew(renewRequest)
        } returns responses

        mockMvc.post(
            path = Path("/v1/sts"),
            headers = HttpHeaders().contentTypeJson(),
            body = renewRequest
        ) {
            statusIsOk()
            responseJsonPath("$.success").isTrue()
            responseJsonPath("$.message").equalsValue("Renewed cert for affiliation=paas namespace=paas-utv name=simple with commonName=org.test.simple")
        }
    }

    @Test
    fun `Renew certificates fails`() {

        val responses = listOf(
            OpenShiftResponse(
                success = false,
                exception = "Failed renewing certificate",
                command = OpenshiftCommand(
                    operationType = OperationType.UPDATE,
                    url = "/update"
                )
            )
        )

        val renewRequest = RenewRequest(
            name = "simple",
            namespace = "paas-utv",
            affiliation = "paas",
            commonName = "org.test.simple",
            ownerReference = OwnerReference()
        )

        every {
            stsRenewFacade.renew(renewRequest)
        } returns responses

        mockMvc.post(
            path = Path("/v1/sts"),
            headers = HttpHeaders().contentTypeJson(),
            body = renewRequest,
            docsIdentifier = "post-v1-sts-failure"
        ) {
            statusIsOk()
            responseJsonPath("$.success").isFalse()
            responseJsonPath("$.message").equalsValue("Failed renewing certificate")
        }
    }
}
