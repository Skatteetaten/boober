package no.skatteetaten.aurora.boober.facade

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import no.skatteetaten.aurora.boober.unit.getKey
import no.skatteetaten.aurora.boober.utils.AuroraConfigSamples.Companion.getAuroraConfigSamples
import no.skatteetaten.aurora.boober.utils.UUIDGenerator
import no.skatteetaten.aurora.boober.utils.getResultFiles
import no.skatteetaten.aurora.boober.utils.openshiftKind
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

    val auroraConfigRef = AuroraConfigRef("paas", "master", "123abb")
    val auroraConfig = getAuroraConfigSamples()

    @BeforeEach
    fun beforeEach() {
        UUIDGenerator.generateId = { "deploy1" }
        every { auroraConfigService.findAuroraConfig(auroraConfigRef) } returns auroraConfig
        every { auroraConfigService.resolveToExactRef(auroraConfigRef) } returns auroraConfigRef
    }

    val adr = ApplicationDeploymentRef("utv", "simple")

    // TOOD: How can we structure code to avoid retrying in this test. It makes it really slow.
    @Test
    fun `deploy simple application`() {

        openShiftMock {

            rule({ path?.endsWith("/groups") }) {
                mockJsonFromFile("groups.json")
            }

            rule({ path?.endsWith("/users") }) {
                mockJsonFromFile("users.json")
            }

            // This is a empty environment so no resources exist
            rule({ method == "GET" }) {
                MockResponse().setResponseCode(404)
            }

            // need to add uid to applicationDeployment for owner reference
            rule({ path?.endsWith("/applicationdeployments") }) {
                replayRequestJsonWithModification(
                    rootPath = "/metadata",
                    key = "uid",
                    newValue = TextNode("123-123")
                )
            }

            // All post/put/delete request just send the result back and assume OK.
            rule {
                MockResponse().setResponseCode(200)
                    .setBody(body)
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

        val generatedObjects = auroraDeployResult.openShiftResponses.mapNotNull {
            it.responseBody
        }
        val resultFiles = adr.getResultFiles()
        val keys = resultFiles.keys

        generatedObjects.forEach {
            val key: String = it.getKey()
            assertThat(keys).contains(key)
            if (it.openshiftKind == "secret") {
                val data = it["data"] as ObjectNode
                data.fields().forEach { (key, _) ->
                    data.put(key, "REMOVED_IN_TEST")
                }
            }
            compareJson(resultFiles[key]!!, it)
        }
        assertThat(generatedObjects.map { it.getKey() }.toSortedSet()).isEqualTo(resultFiles.keys.toSortedSet())
    }
}
