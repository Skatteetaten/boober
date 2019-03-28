package no.skatteetaten.aurora.boober.controller.v1

import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.anyOrNull
import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import no.skatteetaten.aurora.mockmvc.extensions.Path
import no.skatteetaten.aurora.mockmvc.extensions.contentType
import no.skatteetaten.aurora.mockmvc.extensions.get
import no.skatteetaten.aurora.mockmvc.extensions.mock.withContractResponse
import no.skatteetaten.aurora.mockmvc.extensions.patch
import no.skatteetaten.aurora.mockmvc.extensions.put
import no.skatteetaten.aurora.mockmvc.extensions.responseJsonPath
import no.skatteetaten.aurora.mockmvc.extensions.statusIsOk
import org.junit.jupiter.api.BeforeEach
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

    @MockBean
    private lateinit var auroraConfigResponder: AuroraConfigResponder

    private val auroraConfig = AuroraConfig(listOf(AuroraConfigFile("filename", "")), "name", "version")

    private lateinit var fileNamesResponse: Response
    private lateinit var auroraConfigResponse: Response
    private lateinit var auroraConfigFileResponse: Response

    @BeforeEach
    fun setUp() {
        fileNamesResponse = given(auroraConfigResponder.create(any<List<String>>()))
            .withContractResponse("auroraconfig/filenames") {
                willReturn(content)
            }.mockResponse

        auroraConfigResponse = given(auroraConfigResponder.create(any<AuroraConfig>()))
            .withContractResponse("auroraconfig/auroraconfigresponse") {
                willReturn(content)
            }.mockResponse

        auroraConfigFileResponse = given(auroraConfigResponder.create(any<AuroraConfigFile>()))
            .withContractResponse("auroraconfig/auroraconfigfile") {
                willReturn(content)
            }.mockResponse
    }

    @Test
    fun `Patch aurora config`() {
        given(auroraConfigService.patchAuroraConfigFile(any(), any(), any(), anyOrNull())).willReturn(auroraConfig)

        mockMvc.patch(
            path = Path("/v1/auroraconfig/{auroraConfigName}/{fileName}", "paas", "filename"),
            headers = HttpHeaders().contentType(),
            body = mapOf("content" to "test-content")
        ) {
            statusIsOk().responseJsonPath("$").equalsObject(auroraConfigFileResponse)
        }
    }

    @Test
    fun `Get aurora config by name`() {
        given(auroraConfigService.findAuroraConfigFilesForApplicationDeployment(any(), any()))
            .willReturn(auroraConfig.files)

        given(auroraConfigService.findAuroraConfig(any())).willReturn(auroraConfig)

        mockMvc.get(Path("/v1/auroraconfig/{auroraConfigName}", "aos")) {
            statusIsOk().responseJsonPath("$").equalsObject(auroraConfigResponse)
        }
    }

    @Test
    fun `Get aurora config file`() {
        given(auroraConfigService.findAuroraConfigFile(any(), any())).willReturn(auroraConfig.files.first())

        mockMvc.get(Path("/v1/auroraconfig/{auroraConfigName}/{fileName}", "aos", "filename")) {
            statusIsOk().responseJsonPath("$").equalsObject(auroraConfigFileResponse)
        }
    }

    @Test
    fun `Get file names`() {
        given(auroraConfigService.findAuroraConfigFileNames(any())).willReturn(emptyList())

        mockMvc.get(Path("/v1/auroraconfig/aos/filenames")) {
            statusIsOk().responseJsonPath("$").equalsObject(fileNamesResponse)
        }
    }

    @Test
    fun `Update aurora config file`() {
        given(auroraConfigService.updateAuroraConfigFile(any(), any(), any(), anyOrNull())).willReturn(auroraConfig)

        mockMvc.put(
            path = Path("/v1/auroraconfig/{auroraConfigName}/{fileName}", "aos", "filename"),
            headers = HttpHeaders().contentType(),
            body = mapOf("content" to "test-content")
        ) {
            statusIsOk().responseJsonPath("$").equalsObject(auroraConfigFileResponse)
        }
    }

    @Test
    fun `Validate aurora config`() {
        mockMvc.put(
            path = Path("/v1/auroraconfig/{fileName}/validate", "filename"),
            headers = HttpHeaders().contentType(),
            body = mapOf("name" to "name", "files" to emptyList<String>())
        ) {
            statusIsOk().responseJsonPath("$").equalsObject(auroraConfigResponse)
        }
    }
}