package no.skatteetaten.aurora.boober.controller.v1

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import no.skatteetaten.aurora.boober.facade.DeployFacade
import no.skatteetaten.aurora.mockmvc.extensions.Path
import no.skatteetaten.aurora.mockmvc.extensions.contentTypeJson
import no.skatteetaten.aurora.mockmvc.extensions.put
import no.skatteetaten.aurora.mockmvc.extensions.responseJsonPath
import no.skatteetaten.aurora.mockmvc.extensions.statusIsOk
import org.junit.jupiter.api.Test
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.HttpHeaders

@WebMvcTest(controllers = [DeployControllerV1::class])
class DeployControllerV1Test : AbstractControllerTest() {

    @MockkBean
    private lateinit var facade: DeployFacade

    // TODO should the response here be better?
    @Test
    fun `Execute deploy`() {

        val payload = ApplyPayload(applicationDeploymentRefs = listOf(adr))

        val response = stubDeployResult("123")

        every {
            facade.executeDeploy(
                auroraConfigRef,
                payload.applicationDeploymentRefs,
                payload.overridesToAuroraConfigFiles(),
                payload.deploy
            )
        } returns response

        mockMvc.put(
            path = Path("/v1/apply/{affiliation}", auroraConfigRef.name),
            headers = HttpHeaders().contentTypeJson(),
            body = payload
        ) {
            statusIsOk()
            responseJsonPath("$.success").isTrue()
            responseJsonPath("$.items[0].deployId").equalsValue("123")
        }
    }

    @Test
    fun `Execute deploy fails`() {

        val payload = ApplyPayload(applicationDeploymentRefs = listOf(adr))

        val response = stubDeployResult("123", false)

        every {
            facade.executeDeploy(
                auroraConfigRef,
                payload.applicationDeploymentRefs,
                payload.overridesToAuroraConfigFiles(),
                payload.deploy
            )
        } returns response

        mockMvc.put(
            path = Path("/v1/apply/{affiliation}", auroraConfigRef.name),
            docsIdentifier = "put-v1-apply-affailiation-failed",
            headers = HttpHeaders().contentTypeJson(),
            body = payload
        ) {
            statusIsOk()
            responseJsonPath("$.success").isFalse()
        }
    }
}
