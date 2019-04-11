package no.skatteetaten.aurora.boober.unit

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.service.OpenShiftTemplateProcessor
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.utils.ResourceLoader
import no.skatteetaten.aurora.boober.utils.jsonMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OpenShiftTemplateProcessorTest : ResourceLoader() {

    val userDetailsProvider = mockk<UserDetailsProvider>()

    val templateProcessor = OpenShiftTemplateProcessor(
        userDetailsProvider = userDetailsProvider,
        openShiftClient = mockk(),
        mapper = jsonMapper()
    )

    val template = loadResource("jenkins-cluster-persistent-2.0.json")
    val templateJson: JsonNode = jsonMapper().readValue(template)

    @BeforeEach
    fun setupTest() {

        every { userDetailsProvider.getAuthenticatedUser() } returns User("aurora", "token", "Aurora OpenShift")
    }

    @Test
    fun `Should validate when all required parameters are set`() {
        val params = mapOf("AFFILIATION" to "aos", "VOLUME_CAPACITY" to "512Mi")
        assertThat {
            templateProcessor.validateTemplateParameters(templateJson, params)
        }.doesNotThrowAnyException()
    }

    @Test
    fun `Should validate when all required parameters with no defaults are set`() {

        val params = mapOf("AFFILIATION" to "aos")
        assertThat {
            templateProcessor.validateTemplateParameters(templateJson, params)
        }.doesNotThrowAnyException()
    }

    @Test
    fun `Throws exception when parameters are missing`() {

        val errors = templateProcessor.validateTemplateParameters(templateJson, mapOf())
        assertThat(errors).isNotEmpty()
        assertThat(errors[0]).isEqualTo("Required template parameters [AFFILIATION] not set")
    }

    @Test
    fun `Throws exception when extra parameters are provided`() {

        val params = mapOf("AFFILIATION" to "aos", "VOLUME_CAPACITY" to "512Mi", "EXTRA" to "SHOULD NOT BE SET")
        val errors = templateProcessor.validateTemplateParameters(templateJson, params)
        assertThat(errors).isNotEmpty()
    }
}