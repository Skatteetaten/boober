package no.skatteetaten.aurora.boober.controller.v1

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import no.skatteetaten.aurora.boober.facade.AuroraConfigFacade
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.mockmvc.extensions.*
import org.junit.jupiter.api.Test
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.HttpHeaders


@WebMvcTest(controllers = [AuroraConfigControllerV1::class])
class AuroraConfigControllerV1Test : AbstractControllerTest() {

    @MockkBean
    private lateinit var facade: AuroraConfigFacade

    val auroraConfig: AuroraConfig = load("auroraconfig.json")

    @Test
    fun `Patch aurora config`() {

        every {
            facade.patchAuroraConfigFile(any(), any(), any(), any())
        } returns auroraConfig

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
        every {
            facade.findAuroraConfigFiles(any())
        } returns auroraConfig.files

        mockMvc.get(Path("/v1/auroraconfig/{auroraConfigName}", "aos")) {
            statusIsOk()
                .responseJsonPath("$.success").isTrue()
                    .responseJsonPath("$.items[0].name").equalsValue("aos")
                .responseJsonPath("$.items[0].files.length()").equalsValue(1)
        }
    }


    /*
      TODO: How to test this? Need to add ErrorHandler in here?
     */
    @Test
    fun `Get aurora config by name fails if only env specified`() {
        mockMvc.get(Path("/v1/auroraconfig/{auroraConfigName}?environment={env}", "aos", "utv")) {
            statusIsOk()
                    .responseJsonPath("$.success").isFalse()
                    .responseJsonPath("$.items[0].name").equalsValue("filename")
                    .responseJsonPath("$.items[0].contents").equalsValue("contents")
        }
    }

    @Test
    fun `Get aurora config by name for adr DEPRECATED`() {
        every {
            facade.findAuroraConfigFilesForApplicationDeployment(any(), any())
        } returns auroraConfig.files

        mockMvc.get(Path("/v1/auroraconfig/{auroraConfigName}?environment={env}&application={app}", "aos", "utv", "simple")) {
            statusIsOk()
                    .responseJsonPath("$.success").isTrue()
                    .responseJsonPath("$.items[0].name").equalsValue("filename")
                    .responseJsonPath("$.items[0].contents").equalsValue("contents")
        }
    }


    @Test
    fun `Get aurora config by name for adr`() {
        every {
            facade.findAuroraConfigFilesForApplicationDeployment(any(), any())
        } returns auroraConfig.files

        mockMvc.get(Path("/v1/auroraconfig/{auroraConfigName}/{env}/{app}", "aos", "utv", "simple")) {
            statusIsOk()
                    .responseJsonPath("$.success").isTrue()
                    .responseJsonPath("$.items[0].name").equalsValue("filename")
                    .responseJsonPath("$.items[0].contents").equalsValue("contents")
        }
    }
    @Test
    fun `Get aurora config file by file name`() {

        every {
            facade.findAuroraConfigFile(any(), any())
        } returns auroraConfig.files.first()

        mockMvc.get(Path("/v1/auroraconfig/{auroraConfigName}/{fileName}", "aos", "filename")) {
            statusIsOk()
                .responseJsonPath("$.success").isTrue()
                .responseJsonPath("$.items[0].name").equalsValue("filename")
                .responseJsonPath("$.items[0].contents").equalsValue("contents")
        }
    }

    @Test
    fun `Get file names`() {
        every {
            facade.findAuroraConfigFileNames(any())
        } returns listOf("file1")

        mockMvc.get(Path("/v1/auroraconfig/aos/filenames")) {
            statusIsOk()
                .responseJsonPath("$.success").isTrue()
                    .responseJsonPath("$.items.length()").equalsValue(1)
                .responseJsonPath("$.items[0]").equalsValue("file1")
        }
    }

    @Test
    fun `Update aurora config file`() {

        every {
            facade.updateAuroraConfigFile(any(), any(), any(), any())
        } returns auroraConfig

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

        every {
            facade.validateAuroraConfig(any(), any(), any(), any())
        } returns Unit

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
