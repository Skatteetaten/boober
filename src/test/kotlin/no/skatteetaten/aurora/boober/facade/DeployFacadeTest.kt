package no.skatteetaten.aurora.boober.facade

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import no.skatteetaten.aurora.boober.utils.AuroraConfigSamples.Companion.getAuroraConfigSamples
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.bodyAsString
import okhttp3.mockwebserver.MockResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType

private val logger = KotlinLogging.logger {}

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class DeployFacadeTest : AbstractSpringBootTest() {

    @Autowired
    lateinit var facade: DeployFacade

    @MockkBean
    lateinit var auroraConfigService: AuroraConfigService

    val auroraConfigRef = AuroraConfigRef("paas", "master")
    val auroraConfig = getAuroraConfigSamples()

    @BeforeEach
    fun beforeEach() {
        every { auroraConfigService.findAuroraConfig(auroraConfigRef) } returns auroraConfig
        every { auroraConfigService.resolveToExactRef(auroraConfigRef) } returns auroraConfigRef
    }

    val adr = ApplicationDeploymentRef("utv", "simple")

    @Test
    fun `deploy simple application`() {

        openShiftMock {

            rule({ it.path?.endsWith("/groups") ?: false }) {
                mockJsonFromFile("groups.json")
            }

            rule({ it.path?.endsWith("/users") ?: false }) {
                mockJsonFromFile("users.json")
            }

            // This is a empty environment so no resources exist
            rule({ it.method == "GET" }) {
                MockResponse().setResponseCode(404)
            }

            // need to add uid to applicationDeployment for owner reference
            rule({ it.path?.endsWith("/applicationdeployments") ?: false }) {
                val ad: JsonNode = jacksonObjectMapper().readTree(it.bodyAsString())
                val metadata = ad["metadata"]
                (metadata as ObjectNode).set("uid", TextNode("deploy123"))
                MockResponse()
                    .setResponseCode(200)
                    .setBody(jacksonObjectMapper().writeValueAsString(ad))
                    .setHeader("Content-Type", MediaType.APPLICATION_JSON_UTF8_VALUE)
            }

            // All post/put/delete request just send the result back and assume OK.
            rule {
                MockResponse().setResponseCode(200)
                    .setBody(it.body)
                    .setHeader("Content-Type", MediaType.APPLICATION_JSON_UTF8_VALUE)
            }
        }

        bitbucketMock {
            rule {
                MockResponse().setResponseCode(200).setBody("OK!")
            }
        }

        val result = facade.executeDeploy(auroraConfigRef, listOf(adr))
        assertThat(result.size).isEqualTo(1)
        val auroraDeployResult = result.first()
        assertThat(auroraDeployResult.success).isTrue()
    }

    private fun mockJsonFromFile(fileName: String): MockResponse {
        return MockResponse()
            .setBody(loadBufferResource(fileName))
            .setHeader("Content-Type", MediaType.APPLICATION_JSON_UTF8_VALUE)
    }
}
