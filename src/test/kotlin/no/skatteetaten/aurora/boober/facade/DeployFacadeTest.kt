package no.skatteetaten.aurora.boober.facade

import assertk.assertThat
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import assertk.assertions.messageContains
import com.fasterxml.jackson.databind.node.TextNode
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.utils.singleApplicationError
import okhttp3.mockwebserver.MockResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["integrations.openshift.retries=0"]
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
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

        assertThat(result.first().auroraDeploymentSpecInternal)
        // TODO: Should we assert on spec here aswell?
        assertThat(result).auroraDeployResultMatchesFiles()
    }

    @Test
    fun `fail if no application deployment ref`() {
        assertThat { facade.executeDeploy(auroraConfigRef, emptyList()) }.isFailure()
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `fail deploy of application in different cluster`() {

        openShiftMock {

            rule({ path?.endsWith("/groups") }) {
                mockJsonFromFile("groups.json")
            }

            // Should it be able to reuse rules?
            rule(mockOpenShiftUsers)
        }

        assertThat {
            facade.executeDeploy(
                auroraConfigRef, listOf(ApplicationDeploymentRef("utv", "simple")),
                overrides = listOf(
                    AuroraConfigFile(
                        "utv/about.json",
                        contents = """{ "cluster" : "test" }""",
                        override = true
                    )
                )
            )
        }.singleApplicationError("Not valid in this cluster")
    }

    @Test
    fun `fail deploy of application if unused override file`() {

        openShiftMock {

            rule({ path?.endsWith("/groups") }) {
                mockJsonFromFile("groups.json")
            }

            // Should it be able to reuse rules?
            rule(mockOpenShiftUsers)
        }

        assertThat {
            facade.executeDeploy(
                auroraConfigRef, listOf(ApplicationDeploymentRef("utv", "simple")),
                overrides = listOf(
                    AuroraConfigFile(
                        "utv/foobar.json",
                        contents = """{ "version" : "test" }""",
                        override = true
                    )
                )
            )
        }.isFailure()
            .messageContains("Overrides files 'utv/foobar.json' does not apply to any deploymentReference (utv/simple)")
    }
}
