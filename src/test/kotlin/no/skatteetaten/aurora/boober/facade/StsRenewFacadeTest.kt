package no.skatteetaten.aurora.boober.facade

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import com.fkorotkov.kubernetes.newOwnerReference
import mu.KotlinLogging
import okhttp3.mockwebserver.MockResponse
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType.APPLICATION_JSON_UTF8_VALUE

private val logger = KotlinLogging.logger {}


@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class StsRenewFacadeTest : AbstractSpringBootTest() {

    @Autowired
    lateinit var facade: StsRenewFacade

    @Test
    fun `Should renew sts token`() {

        skapMock {
            rule {
                MockResponse()
                    .setBody(loadBufferResource("keystore.jks"))
                    .setHeader("key-password", "ca")
                    .setHeader("store-password", "")
            }
        }

        openShiftMock {
            rule({ it.method == "GET" }) {
                MockResponse().setResponseCode(404)
            }
            rule {
                MockResponse().setResponseCode(200)
                    .setBody(it.body)
                    .setHeader("Content-Type", APPLICATION_JSON_UTF8_VALUE)
            }
        }

        val renewRequest = RenewRequest(
            name = "simple",
            namespace = "paas-utv",
            affiliation = "paas",
            commonName = "org.test.simple",
            ownerReference = newOwnerReference {
                name = "simpe"
                kind = "ApplicationDeployment"
            }
        )

        val result = facade.renew(renewRequest)
        assertThat(result).isNotEmpty()
        assertThat(result.size).isEqualTo(2)
    }
}