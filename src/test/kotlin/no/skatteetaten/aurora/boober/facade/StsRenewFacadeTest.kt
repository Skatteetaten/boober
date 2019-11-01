package no.skatteetaten.aurora.boober.facade

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import com.fkorotkov.kubernetes.newOwnerReference
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.utils.ResourceLoader
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType.APPLICATION_JSON_UTF8_VALUE

private val logger = KotlinLogging.logger {}

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class StsRenewFacadeTest(
    @Value("\${integrations.openshift.port}") val ocpPort: Int,
    @Value("\${integrations.skap.port}") val skapPort: Int
) : ResourceLoader() {

    @Autowired
    lateinit var facade: StsRenewFacade

    //see application.yaml for ports
    val skap = MockWebServer().apply {
        //get certificate
        enqueue(
            MockResponse()
                .setBody(loadBufferResource("keystore.jks"))
                .setHeader("key-password", "ca")
                .setHeader("store-password", "")
        )
        start(skapPort)
    }

    @MockkBean
    lateinit var userDetailsProvider: UserDetailsProvider

    val ocp = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                logger.info { "Mocked http request: $request" }
                return if (request.method == "GET") {
                    MockResponse().setResponseCode(404)
                } else {
                    MockResponse().setResponseCode(200)
                        .setBody(request.body)
                        .setHeader("Content-Type", APPLICATION_JSON_UTF8_VALUE)
                }
            }
        }

        start(ocpPort)
    }

    @After
    fun after() {

        ocp.shutdown()
        skap.shutdown()
    }

    @Test
    fun `Should renew sts token`() {

        every { userDetailsProvider.getAuthenticatedUser() } returns User("hero", "hero")
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