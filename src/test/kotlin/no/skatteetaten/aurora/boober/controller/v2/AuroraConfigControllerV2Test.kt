package no.skatteetaten.aurora.boober.controller.v2

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import no.skatteetaten.aurora.boober.controller.v1.AbstractControllerTest
import no.skatteetaten.aurora.boober.facade.AuroraConfigFacade
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.service.ContextErrors
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

@WebMvcTest(controllers = [AuroraConfigControllerV2::class])
class AuroraConfigControllerV2Test : AbstractControllerTest() {

    @MockkBean
    private lateinit var facade: AuroraConfigFacade

    @Test
    fun `Get aurora config by name`() {
        every {
            facade.findAuroraConfig(auroraConfigRef.copy(refName = "dev"))
        } returns auroraConfig

        mockMvc.get(
            path = Path("/v2/auroraconfig/{auroraConfigName}", auroraConfigRef.name),
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
    fun `Update Aurora config file`() {

        every {
            facade.updateAuroraConfigFile(any(), any(), any(), any())
        } returns AuroraConfigFile("myName", "myContents")

        val payload = """{ "version": "test" }"""
        val fileName = "file/name.json"

        mockMvc.put(
            path = Path("/v2/auroraconfig/test"),
            body = mapOf("content" to payload, "fileName" to fileName),
            headers = HttpHeaders().contentType()
        ) {
            statusIsOk()
                .responseJsonPath("$.success").isTrue()
                .responseJsonPath("$.count").equalsValue(1)
                .responseJsonPath("$.items").isNotEmpty()
        }
    }

    @Test
    fun `Update Aurora config file with validation exception`() {

        every {
            facade.updateAuroraConfigFile(any(), any(), any(), any())
        } throws MultiApplicationValidationException(
            listOf(
                ContextErrors(
                    command = AuroraContextCommand(auroraConfig, adr, auroraConfigRef),
                    errors = listOf(RuntimeException("This is an error"))
                )
            )
        )

        val payload = """{ "abc": "cba" }"""
        val fileName = "file/name.txt"

        mockMvc.put(
            path = Path("/v2/auroraconfig/test"),
            body = mapOf("content" to payload, "fileName" to fileName),
            headers = HttpHeaders().contentType()
        ) {
            status(HttpStatus.BAD_REQUEST)
                .responseJsonPath("$.success").isFalse()
                .responseJsonPath("$.message").contains("An error occurred for one or more applications")
                .responseJsonPath("$.items").isNotEmpty()
        }
    }
}
