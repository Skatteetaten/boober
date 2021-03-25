package no.skatteetaten.aurora.boober.controller.v1

import org.junit.jupiter.api.Test
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.HttpHeaders
import com.fasterxml.jackson.databind.node.TextNode
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import no.skatteetaten.aurora.boober.facade.UserAnnotationFacade
import no.skatteetaten.aurora.mockmvc.extensions.Path
import no.skatteetaten.aurora.mockmvc.extensions.contentType
import no.skatteetaten.aurora.mockmvc.extensions.delete
import no.skatteetaten.aurora.mockmvc.extensions.get
import no.skatteetaten.aurora.mockmvc.extensions.patch
import no.skatteetaten.aurora.mockmvc.extensions.responseJsonPath
import no.skatteetaten.aurora.mockmvc.extensions.statusIsOk

@WebMvcTest(controllers = [UserAnnotationControllerV1::class])
class UserAnnotationControllerV1Test : AbstractControllerTest() {

    @MockkBean
    private lateinit var facade: UserAnnotationFacade

    val existingAnnotations = mapOf("myCustomAnnotation" to TextNode("123abc"), "foo" to TextNode("bar"))
    val singleAnnotation = mapOf("myCustomAnnotation" to TextNode("123abc"))
    val patchedAnnotations = mapOf("myCustomAnnotation" to TextNode("rocks"), "foo" to TextNode("bar"))

    @Test
    fun `Get user annotation with key`() {

        val key = "myCustomAnnotation"

        every { facade.getAnnotations() } returns singleAnnotation

        mockMvc.get(Path("/v1/users/annotations/{key}", key)) {
            statusIsOk()
            responseJsonPath("$.success").isTrue()
            responseJsonPath("$.items[0].myCustomAnnotation").equalsValue("123abc")
        }
    }

    @Test
    fun `Get all annotations`() {

        every { facade.getAnnotations() } returns existingAnnotations

        mockMvc.get(Path("/v1/users/annotations")) {
            statusIsOk()
            responseJsonPath("$.success").isTrue()
            responseJsonPath("$.items[0].myCustomAnnotation").equalsValue("123abc")
            responseJsonPath("$.items[1].foo").equalsValue("bar")
        }
    }

    @Test
    fun `Update annotation`() {

        val key = "myCustomAnnotation"
        val body = TextNode("rocks")

        every { facade.updateAnnotations(key, body) } returns patchedAnnotations

        mockMvc.patch(
            path = Path("/v1/users/annotations/{key}", key),
            headers = HttpHeaders().contentType(),
            body = TextNode("rocks")
        ) {
            statusIsOk()
            responseJsonPath("$.success").isTrue()
            responseJsonPath("$.items[0].myCustomAnnotation").equalsValue("rocks")
            responseJsonPath("$.items[1].foo").equalsValue("bar")
        }
    }

    @Test
    fun `delete annotations`() {
        val key = "foo"

        every { facade.deleteAnnotations(key) } returns singleAnnotation

        mockMvc.delete(
            path = Path("/v1/users/annotations/{key}", key),
            headers = HttpHeaders().contentType()
        ) {
            statusIsOk()
            responseJsonPath("$.success").isTrue()
            responseJsonPath("$.items[0].myCustomAnnotation").equalsValue("123abc")
        }
    }
}
