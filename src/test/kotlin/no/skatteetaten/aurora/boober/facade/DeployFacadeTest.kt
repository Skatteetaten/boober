package no.skatteetaten.aurora.boober.facade

import assertk.assertThat
import com.fasterxml.jackson.databind.node.TextNode
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import okhttp3.mockwebserver.MockResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["integrations.openshift.retries=0"]
)
class DeployFacadeTest : AbstractSpringBootAuroraConfigTest() {

    @Autowired
    lateinit var facade: DeployFacade

    @BeforeEach
    fun beforeDeploy() {
        preprateTestVault("foo", mapOf("latest.properties" to "FOO=bar\nBAR=baz\n".toByteArray()))
    }

    @ParameterizedTest
    @CsvSource(value = ["simple", "easy", "web", "ah", "complex"])
    fun `deploy application`(app: String) {

        skapMock {
            rule {
                MockResponse()
                    .setBody(loadBufferResource("keystore.jks"))
                    .setHeader("key-password", "ca")
                    .setHeader("store-password", "")
            }
        }

        dbhMock {
            rule {
                MockResponse()
                    .setBody(loadBufferResource("dbhResponse.json"))
                    .setResponseCode(200)
                    .setHeader("Content-Type", MediaType.APPLICATION_JSON_UTF8_VALUE)
            }
        }

        cantuMock {
            rule {
                MockResponse()
                    .setBody(""" { "success" : true }""")
                    .setResponseCode(200)
                    .setHeader("Content-Type", MediaType.APPLICATION_JSON_UTF8_VALUE)
            }
        }

        openShiftMock {

            rule({ path?.endsWith("/groups") }) {
                mockJsonFromFile("groups.json")
            }

            // Should it be able to reuse rules?
            rule(mockOpenShiftUsers)

            rule({ method == "GET" && path!!.endsWith("aurora-token") || path!!.endsWith("pvc") }) {
                MockResponse().setResponseCode(200)
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

        val result = facade.executeDeploy(auroraConfigRef, listOf(ApplicationDeploymentRef("utv", app)))

        assertThat(result).auroraDeployResultMatchesFiles()
    }
}
