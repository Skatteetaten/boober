package no.skatteetaten.aurora.boober.facade

import assertk.assertThat
import assertk.assertions.isNotEmpty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newOwnerReference
import com.fkorotkov.kubernetes.newSecret
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.utils.ResourceLoader
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

// TODO: limit the number of classes load here?
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class StsRenewFacadeTest : ResourceLoader() {

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
        start(8082)
    }

    @MockkBean
    lateinit var userDetailsProvider: UserDetailsProvider

    val ocp = MockWebServer().apply {
        //check if secret exist
        enqueue(MockResponse().setResponseCode(404))

        //save secret,TODO: replace this with just returning what is sent in?
        enqueue(MockResponse().setBody(jacksonObjectMapper().writeValueAsString(
            newSecret {
                metadata {
                    name = "simple-cert"
                    namespace = "paas-utv"
                }
            }
        )))

        //deploy request
        enqueue(MockResponse().setBody(""" { "success" : "true" }"""))

        start(8083)
    }

    @Test
    fun `foo`() {

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
    }
}