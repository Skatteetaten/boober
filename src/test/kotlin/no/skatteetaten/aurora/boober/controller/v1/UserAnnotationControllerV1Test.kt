package no.skatteetaten.aurora.boober.controller.v1

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.service.UserAnnotationService
import no.skatteetaten.aurora.boober.utils.toJson
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup

class UserAnnotationControllerV1Test {

    private val userAnnotationService = mockk<UserAnnotationService>()
    private val controller = UserAnnotationControllerV1(userAnnotationService, UserAnnotationResponder())
    private val mockMvc = standaloneSetup(controller).build()

    @AfterEach
    fun tearDown() {
        clearMocks(userAnnotationService)
    }

    @Test
    fun `Add user annotations`() {
        val jsonEntries = """{"key": "value"}"""
        val entries = jacksonObjectMapper().readValue<Map<String, Any>>(jsonEntries)

        every { userAnnotationService.addAnnotations("filters", entries) } returns mapOf("filters" to """{"key":"value"}""".toJson())

        val response = mockMvc.perform(
            patch("/v1/users/annotations/{key}", "filters")
                .contentType(MediaType.APPLICATION_JSON_UTF8)
                .content(jsonEntries)
        )

        response.andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].filters.key").value("value"))
    }

    @Test
    fun `Get user annotations`() {
        every { userAnnotationService.getAnnotations("filters") } returns mapOf("filters" to """{"key":"value"}""".toJson())

        val response = mockMvc.perform(get("/v1/users/annotations/{key}", "filters"))

        response.andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.items[0].filters.key").value("value"))
    }
}