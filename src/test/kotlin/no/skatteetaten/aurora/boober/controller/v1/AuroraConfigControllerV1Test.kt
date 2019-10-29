package no.skatteetaten.aurora.boober.controller.v1

import no.skatteetaten.aurora.mockmvc.extensions.get

/*
@AutoConfigureRestDocs
@WebMvcTest(controllers = [AuroraConfigControllerV1::class], secure = false)
class AuroraConfigControllerV1Test(@Autowired private val mockMvc: MockMvc) {

    @MockBean
    private lateinit var auroraConfigService: AuroraConfigService

    @Test
    fun `Patch aurora config`() {
        given(auroraConfigService.patchAuroraConfigFile(any(), any(), any(), anyOrNull()))
            .withContractResponse("auroraconfig/auroraconfig") { willReturn(content) }

        mockMvc.patch(
            path = Path("/v1/auroraconfig/{auroraConfigName}/{fileName}", "paas", "filename"),
            headers = HttpHeaders().contentType(),
            body = mapOf("content" to "test-content")
        ) {
            statusIsOk()
                .responseJsonPath("$.success").isTrue()
                .responseJsonPath("$.items[0].name").equalsValue("filename")
                .responseJsonPath("$.items[0].contents").equalsValue("contents")
        }
    }

    @Test
    fun `Get aurora config by name`() {
        given(auroraConfigService.findAuroraConfig(any()))
            .withContractResponse("auroraconfig/auroraconfig") { willReturn(content) }

        mockMvc.get(Path("/v1/auroraconfig/{auroraConfigName}", "aos")) {
            statusIsOk()
                .responseJsonPath("$.success").isTrue()
                .responseJsonPath("$.items[0].name").equalsValue("paas")
                .responseJsonPath("$.items[0].files.length()").equalsValue(1)
        }
    }

    @Test
    fun `Get aurora config file by file name`() {
        given(auroraConfigService.findAuroraConfigFile(any(), any()))
            .withNullableContractResponse("auroraconfig/auroraconfigfile") { willReturn(content) }

        mockMvc.get(Path("/v1/auroraconfig/{auroraConfigName}/{fileName}", "aos", "filename")) {
            statusIsOk()
                .responseJsonPath("$.success").isTrue()
                .responseJsonPath("$.items[0].name").equalsValue("filename")
                .responseJsonPath("$.items[0].contents").equalsValue("contents")
        }
    }

    @Test
    fun `Get file names`() {
        given(auroraConfigService.findAuroraConfigFileNames(any()))
            .withContractResponse("auroraconfig/filenames") { willReturn(content) }

        mockMvc.get(Path("/v1/auroraconfig/aos/filenames")) {
            statusIsOk()
                .responseJsonPath("$.success").isTrue()
                .responseJsonPath("$.items.length()").equalsValue(4)
                .responseJsonPath("$.items[0]").equalsValue("file1")
        }
    }

    @Test
    fun `Update aurora config file`() {
        given(auroraConfigService.updateAuroraConfigFile(any(), any(), any(), anyOrNull()))
            .withContractResponse("auroraconfig/auroraconfigresponse") { willReturn(content) }

        mockMvc.put(
            path = Path("/v1/auroraconfig/{auroraConfigName}/{fileName}", "aos", "filename"),
            headers = HttpHeaders().contentType(),
            body = mapOf("content" to "test-content")
        ) {
            statusIsOk()
                .responseJsonPath("$.success").isTrue()
                .responseJsonPath("$.items[0].name").equalsValue("filename")
                .responseJsonPath("$.items[0].type").equalsValue("BASE")
        }
    }

    @Test
    fun `Validate aurora config`() {
        mockMvc.put(
            path = Path("/v1/auroraconfig/{fileName}/validate", "filename"),
            headers = HttpHeaders().contentType(),
            body = mapOf("name" to "name", "files" to emptyList<String>())
        ) {
            statusIsOk()
                .responseJsonPath("$.success").isTrue()
                .responseJsonPath("$.items[0].name").equalsValue("filename")
        }
    }
}
*/
