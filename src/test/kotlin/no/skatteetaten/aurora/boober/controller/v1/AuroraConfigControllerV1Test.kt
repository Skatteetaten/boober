package no.skatteetaten.aurora.boober.controller.v1

import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.anyOrNull
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import no.skatteetaten.aurora.mockmvc.extensions.Path
import no.skatteetaten.aurora.mockmvc.extensions.contentType
import no.skatteetaten.aurora.mockmvc.extensions.get
import no.skatteetaten.aurora.mockmvc.extensions.mock.withContractResponse
import no.skatteetaten.aurora.mockmvc.extensions.mock.withNullableContractResponse
import no.skatteetaten.aurora.mockmvc.extensions.patch
import no.skatteetaten.aurora.mockmvc.extensions.put
import no.skatteetaten.aurora.mockmvc.extensions.responseJsonPath
import no.skatteetaten.aurora.mockmvc.extensions.statusIsOk
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc

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
