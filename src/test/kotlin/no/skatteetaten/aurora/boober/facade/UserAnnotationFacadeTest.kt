package no.skatteetaten.aurora.boober.facade

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.openshift.metadata
import com.fkorotkov.openshift.newUser
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.openshift.token.ServiceAccountTokenProvider
import no.skatteetaten.aurora.boober.utils.ResourceLoader
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
class UserAnnotationFacadeTest(
    @Value("\${integrations.openshift.port}") val ocpPort: Int
) : ResourceLoader() {

    @Autowired
    lateinit var facade: UserAnnotationFacade

    @MockkBean
    lateinit var userDetailsProvider: UserDetailsProvider

    @MockkBean
    lateinit var serviceAccountTokenProvider: ServiceAccountTokenProvider
    lateinit var ocp: MockWebServer

    @After
    fun afterEach() {

        ocp.shutdown()
    }

    @Test
    fun `get user annotations`() {

        every { serviceAccountTokenProvider.getToken() } returns "auth token"
        every { userDetailsProvider.getAuthenticatedUser() } returns User("hero", "hero")
        val ocp = MockWebServer().apply {

            enqueue(
                MockResponse().setBody(jacksonObjectMapper().writeValueAsString(
                    newUser {
                        metadata {
                            name = "hero"
                            annotations = mapOf(
                                "favorite" to "R2D2"
                            )
                        }

                    }
                ))
                    .setHeader("Content-Type", APPLICATION_JSON_UTF8_VALUE)
            )

            start(ocpPort)
        }

        val annotations = facade.getAnnotations()
        assertThat(annotations.size).isEqualTo(1)
        assertThat(annotations["favorite"]).isEqualTo(TextNode("R2D2"))
    }

    // TODO: Address already in use when running both tests
    @Test
    fun `update annotations`() {

        every { serviceAccountTokenProvider.getToken() } returns "auth token"
        every { userDetailsProvider.getAuthenticatedUser() } returns User("hero", "hero")
        val ocp = MockWebServer().apply {

            enqueue(
                MockResponse().setBody(jacksonObjectMapper().writeValueAsString(
                    newUser {
                        metadata {
                            name = "hero"
                            annotations = mapOf(
                                "favorite" to "C3PO"
                            )
                        }

                    }
                ))
                    .setHeader("Content-Type", APPLICATION_JSON_UTF8_VALUE)
            )

            start(ocpPort)
        }

        val annotations = facade.updateAnnotations("favorite", TextNode("C3PO"))
        assertThat(annotations.size).isEqualTo(1)
        assertThat(annotations["favorite"]).isEqualTo(TextNode("C3PO"))
    }
}