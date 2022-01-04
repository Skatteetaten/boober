package no.skatteetaten.aurora.boober.facade

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import okhttp3.mockwebserver.MockResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["integrations.openshift.retries=0"]
)
// @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class DeploymentFacadeTest : AbstractSpringBootAuroraConfigTest() {

    @Autowired
    lateinit var facade: DeploymentFacade

    @BeforeEach
    fun beforeEachTest() {
        applicationDeploymentGenerationMock("1234567890")
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
