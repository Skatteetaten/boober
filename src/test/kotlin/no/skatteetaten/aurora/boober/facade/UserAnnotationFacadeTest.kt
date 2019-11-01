package no.skatteetaten.aurora.boober.facade

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.openshift.metadata
import com.fkorotkov.openshift.newUser
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
class UserAnnotationFacadeTest : AbstractSpringBootTest() {

    @Autowired
    lateinit var facade: UserAnnotationFacade

    fun ocpMockUserAnnotations(annotationMap: Map<String, String>): MockResponse {
        return MockResponse().setBody(jacksonObjectMapper().writeValueAsString(
            newUser {
                metadata {
                    name = "hero"
                    annotations = annotationMap
                }
            }
        )).setHeader("Content-Type", APPLICATION_JSON_UTF8_VALUE)
    }

    @Test
    fun `get user annotations`() {

        openShiftMock {
            rule {
                ocpMockUserAnnotations(mapOf("favorite" to "R2D2"))
            }
        }

        val annotations = facade.getAnnotations()
        assertThat(annotations.size).isEqualTo(1)
        assertThat(annotations["favorite"]).isEqualTo(TextNode("R2D2"))
    }

    @Test
    fun `update annotations`() {
        openShiftMock {
            rule({ it.method == "GET" }) {
                ocpMockUserAnnotations(mapOf("favorite" to "R2D2"))
            }
            rule({ it.method == "PATCH" }) {
                ocpMockUserAnnotations(mapOf("favorite" to "C3PO"))
            }
        }
        val before = facade.getAnnotations()
        assertThat(before.size).isEqualTo(1)
        assertThat(before["favorite"]).isEqualTo(TextNode("R2D2"))

        val annotations = facade.updateAnnotations("favorite", TextNode("C3PO"))
        assertThat(annotations.size).isEqualTo(1)
        assertThat(annotations["favorite"]).isEqualTo(TextNode("C3PO"))
    }

    @Test
    fun `delete annotations`() {

        openShiftMock {
            rule({ it.method == "GET" }) {
                ocpMockUserAnnotations(mapOf("favorite" to "C3PO", "master" to "obi wan"))
            }
            rule({ it.method == "PATCH" }) {
                ocpMockUserAnnotations(mapOf("favorite" to "C3PO"))
            }
        }
        val before = facade.getAnnotations()
        assertThat(before.size).isEqualTo(2)

        val after = facade.deleteAnnotations("master")
        assertThat(after.size).isEqualTo(1)
        assertThat(after["favorite"]).isEqualTo(TextNode("C3PO"))
    }
}
