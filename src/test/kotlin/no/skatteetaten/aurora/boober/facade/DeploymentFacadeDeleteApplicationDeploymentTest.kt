package no.skatteetaten.aurora.boober.facade

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.model.ApplicationRef
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftRestTemplateWrapper
import no.skatteetaten.aurora.boober.service.openshift.token.TokenProvider
import okhttp3.mockwebserver.MockResponse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.web.client.RestTemplateBuilder

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["integrations.openshift.retries=0"]
)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class DeploymentFacadeDeleteApplicationDeploymentTest : AbstractSpringBootTest() {

    @Autowired
    lateinit var facade: DeploymentFacade

    @Test
    fun `should delete application`() {

        openShiftMock {

            // AD exist
            rule({ method == "GET" }) {
                json("""{ "success" : "true" }""")
            }

            // Delete command success
            rule({ method == "DELETE" }) {
                json("""{ "success" : "true" }""")
            }
        }

        val resultList = facade.executeDelete(listOf(ApplicationRef("paas-utv", "simple")))
        assertThat(resultList.size).isEqualTo(1)
        val result = resultList.first()
        assertThat(result.success).isTrue()
        assertThat(result.message).contains("Application was successfully deleted")
    }

    @Test
    fun `should handle bad request when deleting application`() {

        openShiftMock {
            // AD exist
            rule({ method == "GET" }) {
                json("""{ "success" : "true" }""")
            }

            // Delete command success
            rule({ method == "DELETE" }) {
                MockResponse().setResponseCode(400).setBody("Could not delete app")
            }
        }

        val resultList = deploymentFacade().executeDelete(listOf(ApplicationRef("paas-utv", "simple")))
        assertThat(resultList.size).isEqualTo(1)
        val result = resultList.first()
        assertThat(result.success).isFalse()
        assertThat(result.message).contains("openShiftCommunicationError code=400")
        // TODO: Better error message here?
    }

    @Test
    fun `should handle error when deleting application`() {

        openShiftMock {
            // AD exist
            rule({ method == "GET" }) {
                json("""{ "success" : "true" }""")
            }

            // Delete command success
            rule({ method == "DELETE" }) {
                MockResponse().setResponseCode(404)
            }
        }

        val resultList = deploymentFacade().executeDelete(listOf(ApplicationRef("paas-utv", "simple")))
        assertThat(resultList.size).isEqualTo(1)
        val result = resultList.first()
        assertThat(result.success).isFalse()
        assertThat(result.message).contains("ApplicationDeployment does not exist")
        // TODO: Is this right?
    }

    private fun deploymentFacade(): DeploymentFacade {
        val tokenProvider = mockk<TokenProvider>().apply {
            every { getToken() } returns "test-token"
        }
        val restTemplate = RestTemplateBuilder().rootUri("http://localhost:$ocpPort").build()
        val client = OpenShiftResourceClient(tokenProvider, OpenShiftRestTemplateWrapper(restTemplate))
        return DeploymentFacade(
            mockk(),
            mockk(),
            OpenShiftClient(client, client, jacksonObjectMapper())
        )
    }
}
