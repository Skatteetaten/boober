package no.skatteetaten.aurora.boober.controller.v1

// TODO: Fix
/*
@AutoConfigureRestDocs
@WebMvcTest(controllers = [UserAnnotationControllerV1::class], secure = false)
class UserAnnotationControllerV1Test(@Autowired private val mockMvc: MockMvc) {

    @MockBean
    private lateinit var userAnnotationService: UserAnnotationService

    val existingAnnotations = mapOf("myCustomAnnotation" to TextNode("123abc"), "foo" to TextNode("bar"))
    val singleAnnotation = mapOf("myCustomAnnotation" to TextNode("123abc"))
    val patchedAnnotations = mapOf("myCustomAnnotation" to TextNode("rocks"), "foo" to TextNode("bar"))

    @Test
    fun `Get user annotation with key`() {

        val key = "myCustomAnnotation"
        given(userAnnotationService.getAnnotations()).willReturn(singleAnnotation)
        val annotations = given(KeyValueResponse<JsonNode>(items = singleAnnotation)).withContractResponse("userannotation/single") {
            willReturn(content)
        }.mockResponse

        mockMvc.get(Path("/v1/users/annotations/{key}", key)) {
            statusIsOk().responseJsonPath("$").equalsObject(annotations)
        }
    }

    @Test
    fun `Get all annotations`() {

        given(userAnnotationService.getAnnotations()).willReturn(existingAnnotations)
        val annotations = given(KeyValueResponse<JsonNode>(items = existingAnnotations)).withContractResponse("userannotation/all") {
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
        val annotations = given(KeyValueResponse<JsonNode>(items = patchedAnnotations)).withContractResponse("userannotation/updated") {
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
        val annotations = given(KeyValueResponse<JsonNode>(items = singleAnnotation)).withContractResponse("userannotation/single") {
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

 */
