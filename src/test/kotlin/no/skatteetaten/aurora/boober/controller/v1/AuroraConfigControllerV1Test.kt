package no.skatteetaten.aurora.boober.controller.v1

import assertk.assertThat
import assertk.assertions.isFailure
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import no.skatteetaten.aurora.boober.facade.AuroraConfigFacade
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.service.ContextCreationErrors
import no.skatteetaten.aurora.boober.service.MultiApplicationValidationException
import no.skatteetaten.aurora.mockmvc.extensions.Path
import no.skatteetaten.aurora.mockmvc.extensions.contentType
import no.skatteetaten.aurora.mockmvc.extensions.get
import no.skatteetaten.aurora.mockmvc.extensions.put
import no.skatteetaten.aurora.mockmvc.extensions.responseJsonPath
import no.skatteetaten.aurora.mockmvc.extensions.status
import no.skatteetaten.aurora.mockmvc.extensions.statusIsOk
import org.junit.jupiter.api.Test
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus

@WebMvcTest(controllers = [AuroraConfigControllerV1::class])
class AuroraConfigControllerV1Test : AbstractControllerTest() {

    @MockkBean
    private lateinit var facade: AuroraConfigFacade

    @Test
    fun `Get aurora config by name`() {
        every {
            facade.findAuroraConfig(auroraConfigRef)
        } returns auroraConfig

        mockMvc.get(Path("/v1/auroraconfig/{auroraConfigName}", auroraConfigRef.name)) {
            statusIsOk()
            responseJsonPath("$.success").isTrue()
            responseJsonPath("$.items[0].name").equalsValue(auroraConfigRef.name)
            responseJsonPath("$.items[0].files").isNotEmpty()
        }
    }

    @Test
    fun `Get aurora config by name for another branch with queryparam`() {
        every {
            facade.findAuroraConfig(auroraConfigRef.copy(refName = "dev"))
        } returns auroraConfig

        mockMvc.get(
            path = Path("/v1/auroraconfig/{auroraConfigName}?reference={reference}", auroraConfigRef.name, "dev"),
            docsIdentifier = "reference-queryparam"
        ) {
            statusIsOk()
            responseJsonPath("$.success").isTrue()
            responseJsonPath("$.items[0].name").equalsValue(auroraConfigRef.name)
            responseJsonPath("$.items[0].files").isNotEmpty()
        }
    }

    @Test
    fun `Get aurora config by name for another branch with header`() {
        every {
            facade.findAuroraConfig(auroraConfigRef.copy(refName = "dev"))
        } returns auroraConfig

        mockMvc.get(
            path = Path("/v1/auroraconfig/{auroraConfigName}", auroraConfigRef.name),
            headers = HttpHeaders().apply {
                set("Ref-Name", "dev")
            },
            docsIdentifier = "reference-header"
        ) {
            statusIsOk()
            responseJsonPath("$.success").isTrue()
            responseJsonPath("$.items[0].name").equalsValue(auroraConfigRef.name)
            responseJsonPath("$.items[0].files").isNotEmpty()
        }
    }

    @Test
    fun `Get aurora config by name fails if only env specified`() {
        assertThat {
            mockMvc.get(Path("/v1/auroraconfig/{auroraConfigName}?environment={env}", auroraConfigRef.name, "utv")) {
                statusIsOk()
                responseJsonPath("$.success").isFalse()
                responseJsonPath("$.items[0].name").equalsValue("filename")
                responseJsonPath("$.items[0].contents").equalsValue("contents")
            }
        }.isFailure()
    }

    @Test
    fun `Get aurora config by name for adr DEPRECATED`() {
        every {
            facade.findAuroraConfigFilesForApplicationDeployment(auroraConfigRef, adr)
        } returns auroraConfig.getFilesForApplication(adr)

        mockMvc.get(
            Path(
                "/v1/auroraconfig/{auroraConfigName}?environment={env}&application={app}",
                auroraConfigRef.name, adr.environment, adr.application
            )
        ) {
            statusIsOk()
            responseJsonPath("$.success").isTrue()
            responseJsonPath("$.items.length()").equalsValue(4)
            responseJsonPath("$.items[0].name").equalsValue("about.json")
            responseJsonPath("$.items[1].name").equalsValue("simple.json")
            responseJsonPath("$.items[2].name").equalsValue("utv/about.json")
            responseJsonPath("$.items[3].name").equalsValue("utv/simple.json")
        }
    }

    @Test
    fun `Get aurora config by name for adr`() {
        every {
            facade.findAuroraConfigFilesForApplicationDeployment(auroraConfigRef, adr)
        } returns auroraConfig.getFilesForApplication(adr)

        mockMvc.get(
            Path(
                "/v1/auroraconfig/{auroraConfigName}/files/{env}/{app}",
                auroraConfigRef.name, adr.environment, adr.application
            )
        ) {
            statusIsOk()
            responseJsonPath("$.success").isTrue()
            responseJsonPath("$.items.length()").equalsValue(4)
            responseJsonPath("$.items[0].name").equalsValue("about.json")
            responseJsonPath("$.items[1].name").equalsValue("simple.json")
            responseJsonPath("$.items[2].name").equalsValue("utv/about.json")
            responseJsonPath("$.items[3].name").equalsValue("utv/simple.json")
        }
    }

    @Test
    fun `Get aurora config file by file name`() {

        val fileName = "about.json"
        val fileContent = auroraConfig.files.find { it.name == fileName }!!
        every {
            facade.findAuroraConfigFile(auroraConfigRef, fileName)
        } returns fileContent

        mockMvc.get(Path("/v1/auroraconfig/{auroraConfigName}/{fileName}", auroraConfigRef.name, fileName)) {
            statusIsOk()
            responseJsonPath("$.success").isTrue()
            responseJsonPath("$.items[0].name").equalsValue(fileName)
            responseJsonPath("$.items[0].contents").equalsValue(fileContent.contents)
        }
    }

    @Test
    fun `Get file names`() {
        every {
            facade.findAuroraConfigFileNames(auroraConfigRef)
        } returns auroraConfig.files.map { it.name }

        mockMvc.get(Path("/v1/auroraconfig/{auroraConfig}/filenames", auroraConfigRef.name)) {
            statusIsOk()
            responseJsonPath("$.success").isTrue()
            responseJsonPath("$.items").isNotEmpty()
        }
    }

    @Test
    fun `Update aurora config file`() {

        // This cannot contain a /, the wiremock test framework we use here will not support it
        val fileName = "simple.json"
        val content = """{ "version" : "test" }"""

        every {
            facade.updateAuroraConfigFile(auroraConfigRef, fileName, content, null)
        } returns AuroraConfigFile(fileName, content)

        mockMvc.put(
            path = Path("/v1/auroraconfig/{auroraConfigName}/{fileName}", auroraConfigRef.name, fileName),
            headers = HttpHeaders().contentType(),
            body = mapOf("content" to content)
        ) {
            statusIsOk()
            responseJsonPath("$.success").isTrue()
            responseJsonPath("$.items[0].name").equalsValue(fileName)
            responseJsonPath("$.items[0].contents").equalsValue(content)
        }
    }

    @Test
    fun `Validate aurora config no errors and no warnings`() {

        every {
            facade.validateAuroraConfig(auroraConfig, false, auroraConfigRef)
        } returns emptyMap()

        mockMvc.put(
            path = Path("/v1/auroraconfig/{auroraConfig}/validate", auroraConfigRef.name),
            headers = HttpHeaders().contentType(),
            docsIdentifier = "validate_auroraconfig",
            body = jacksonObjectMapper().writeValueAsString(auroraConfig)
        ) {
            statusIsOk()
            responseJsonPath("$.success").isTrue()
            responseJsonPath("$.items").isEmpty()
        }
    }

    @Test
    fun `Validate aurora config warnings`() {

        every {
            facade.validateAuroraConfig(auroraConfig, false, auroraConfigRef)
        } returns mapOf(adr to listOf("This is a warning"))

        mockMvc.put(
            path = Path("/v1/auroraconfig/{auroraConfig}/validate", auroraConfigRef.name),
            headers = HttpHeaders().contentType(),
            docsIdentifier = "validate_auroraconfig_warnings",
            body = jacksonObjectMapper().writeValueAsString(auroraConfig)
        ) {
            statusIsOk()
            responseJsonPath("$.success").isTrue()
            responseJsonPath("$.items[0].details[0].message").equalsValue("This is a warning")
            responseJsonPath("$.items[0].details[0].type").equalsValue("WARNING")
        }
    }

    @Test
    fun `Validate aurora config errors`() {

        every {
            facade.validateAuroraConfig(auroraConfig, false, auroraConfigRef)
        } throws MultiApplicationValidationException(
            listOf(
                ContextCreationErrors(
                    command = AuroraContextCommand(auroraConfig, adr, auroraConfigRef),
                    errors = listOf(RuntimeException("This is an error"))
                )
            )
        )

        mockMvc.put(
            path = Path("/v1/auroraconfig/{auroraConfig}/validate", auroraConfigRef.name),
            headers = HttpHeaders().contentType(),
            docsIdentifier = "validate_auroraconfig_warnings",
            body = jacksonObjectMapper().writeValueAsString(auroraConfig)
        ) {
            status(HttpStatus.BAD_REQUEST)
            responseJsonPath("$.success").isFalse()
            responseJsonPath("$.items[0].details[0].message").equalsValue("This is an error")
            responseJsonPath("$.items[0].details[0].type").equalsValue("GENERIC")
        }
    }

    @Test
    fun `Validate aurora config merge with existing`() {

        every {
            facade.validateAuroraConfig(
                localAuroraConfig = any(),
                resourceValidation = false,
                auroraConfigRef = auroraConfigRef, mergeWithRemoteConfig = true
            )
        } returns emptyMap()

        mockMvc.put(
            path = Path("/v1/auroraconfig/{auroraConfig}/validate?mergeWithRemoteConfig=true", auroraConfigRef.name)
        ) {
            statusIsOk()
            responseJsonPath("$.success").isTrue()
        }
    }
}
