package no.skatteetaten.aurora.boober.facade

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.ApplicationRef
import okhttp3.mockwebserver.MockResponse
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["integrations.openshift.retries=0"]
)
class DeploymentFacadeTest : AbstractSpringBootTest() {

    @Autowired
    lateinit var facade: DeploymentFacade

    @Test
    fun `should delete application`() {

        openShiftMock {

            //AD exist
            rule(({ method == "GET" })) {
                json("""{ "success" : "true" }""")
            }

            //Delete command success
            rule(({ method == "DELETE" })) {
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
            //AD exist
            rule(({ method == "GET" })) {
                json("""{ "success" : "true" }""")
            }

            //Delete command success
            rule(({ method == "DELETE" })) {
                MockResponse().setResponseCode(400).setBody("Could not delete app")
            }
        }

        val resultList = facade.executeDelete(listOf(ApplicationRef("paas-utv", "simple")))
        assertThat(resultList.size).isEqualTo(1)
        val result = resultList.first()
        assertThat(result.success).isFalse()
        assertThat(result.message).contains("openShiftCommunicationError code=400")
        // TODO: Better error message here?
    }

    @Test
    fun `should handle error when deleting application`() {

        openShiftMock {
            //AD exist
            rule(({ method == "GET" })) {
                json("""{ "success" : "true" }""")
            }

            //Delete command success
            rule(({ method == "DELETE" })) {
                MockResponse().setResponseCode(404)
            }
        }

        val resultList = facade.executeDelete(listOf(ApplicationRef("paas-utv", "simple")))
        assertThat(resultList.size).isEqualTo(1)
        val result = resultList.first()
        assertThat(result.success).isFalse()
        assertThat(result.message).contains("ApplicationDeployment does not exist")
        // TODO: Is this right?
    }

    @Test
    fun `deployment should exist`() {

        openShiftMock {
            rule {
                json("""{ "success" : "true" }""")
            }
        }

        val resultList = facade.deploymentExist(auroraConfigRef, listOf(ApplicationDeploymentRef("utv", "simple")))

        assertThat(resultList.size).isEqualTo(1)
        val result = resultList.first()
        assertThat(result.exists).isTrue()
        assertThat(result.success).isTrue()
    }

    @Test
    fun `deployment not found`() {

        openShiftMock {
            rule {
                MockResponse().setResponseCode(404)
            }
        }

        val resultList = facade.deploymentExist(auroraConfigRef, listOf(ApplicationDeploymentRef("utv", "simple")))

        assertThat(resultList.size).isEqualTo(1)
        val result = resultList.first()
        assertThat(result.exists).isFalse()
        assertThat(result.success).isTrue()
    }

    @Test
    fun `deployment forbidden`() {

        openShiftMock {
            rule {
                MockResponse().setResponseCode(403)
            }
        }

        val resultList = facade.deploymentExist(auroraConfigRef, listOf(ApplicationDeploymentRef("utv", "simple")))

        assertThat(resultList.size).isEqualTo(1)
        val result = resultList.first()
        assertThat(result.exists).isFalse()
        assertThat(result.success).isTrue()
    }

    @Test
    fun `deployment handle error`() {

        openShiftMock {
            rule {
                MockResponse().setResponseCode(400).setBody("Bad request")
            }
        }

        val resultList = facade.deploymentExist(auroraConfigRef, listOf(ApplicationDeploymentRef("utv", "simple")))

        assertThat(resultList.size).isEqualTo(1)
        val result = resultList.first()
        assertThat(result.exists).isFalse()
        assertThat(result.success).isFalse()
    }
}

