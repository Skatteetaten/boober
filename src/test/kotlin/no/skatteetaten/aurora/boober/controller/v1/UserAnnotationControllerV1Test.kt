package no.skatteetaten.aurora.boober.controller.v1

import com.fasterxml.jackson.databind.node.TextNode
import com.nhaarman.mockito_kotlin.given
import no.skatteetaten.aurora.boober.service.UserAnnotationService
import no.skatteetaten.aurora.mockmvc.extensions.Path
import no.skatteetaten.aurora.mockmvc.extensions.contentType
import no.skatteetaten.aurora.mockmvc.extensions.delete
import no.skatteetaten.aurora.mockmvc.extensions.get
import no.skatteetaten.aurora.mockmvc.extensions.mock.withContractResponse
import no.skatteetaten.aurora.mockmvc.extensions.patch
import no.skatteetaten.aurora.mockmvc.extensions.responseJsonPath
import no.skatteetaten.aurora.mockmvc.extensions.statusIsOk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc

@AutoConfigureRestDocs
@WebMvcTest(controllers = [UserAnnotationControllerV1::class], secure = false)
class UserAnnotationControllerV1Test(@Autowired private val mockMvc: MockMvc) {

    @MockBean
    private lateinit var userAnnotationService: UserAnnotationService

    @MockBean
    private lateinit var responder: UserAnnotationResponder

    val existingAnnotations = mapOf("myCustomAnnotation" to TextNode("123abc"), "foo" to TextNode("bar"))
    val singleAnnotation = mapOf("myCustomAnnotation" to TextNode("123abc"))
    val patchedAnnotations = mapOf("myCustomAnnotation" to TextNode("rocks"), "foo" to TextNode("bar"))

    @Test
    fun `Get user annoation with key`() {

        val key = "myCustomAnnotation"
        given(userAnnotationService.getAnnotations()).willReturn(singleAnnotation)
        val annotations = given(responder.create(singleAnnotation)).withContractResponse("userannotation/single") {
            willReturn(content)
        }.mockResponse

        mockMvc.get(Path("/v1/users/annotations/{key}", key)) {
            statusIsOk().responseJsonPath("$").equalsObject(annotations)
        }
    }

    @Test
    fun `Get all annotions`() {

        given(userAnnotationService.getAnnotations()).willReturn(existingAnnotations)
        val annotations = given(responder.create(existingAnnotations)).withContractResponse("userannotation/all") {
            willReturn(content)
        }.mockResponse

        mockMvc.get(Path("/v1/users/annotations")) {
            statusIsOk().responseJsonPath("$").equalsObject(annotations)
        }
    }

    @Test
    fun `Update annotation`() {

        val key = "myCustomAnnotation"
        val body = TextNode("rocks")

        given(userAnnotationService.updateAnnotations(key, body)).willReturn(
            patchedAnnotations
        )
        val annotations = given(responder.create(patchedAnnotations)).withContractResponse("userannotation/updated") {
            willReturn(content)
        }.mockResponse

        mockMvc.patch(
            path = Path("/v1/users/annotations/{key}", key),
            headers = HttpHeaders().contentType(),
            body = TextNode("rocks")
        ) {
            statusIsOk().responseJsonPath("$").equalsObject(annotations)
        }
    }

    @Test
    fun `delete annotations`() {
        val key = "foo"

        given(userAnnotationService.deleteAnnotations(key)).willReturn(singleAnnotation)
        val annotations = given(responder.create(singleAnnotation)).withContractResponse("userannotation/single") {
            willReturn(content)
        }.mockResponse

        mockMvc.delete(
            path = Path("/v1/users/annotations/{key}", key),
            headers = HttpHeaders().contentType()
        ) {
            statusIsOk().responseJsonPath("$").equalsObject(annotations)
        }
    }
}