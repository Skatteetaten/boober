package no.skatteetaten.aurora.boober.unit

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup
import com.fasterxml.jackson.databind.node.TextNode
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.controller.v1.UserAnnotationControllerV1
import no.skatteetaten.aurora.boober.facade.UserAnnotationFacade
import no.skatteetaten.aurora.boober.utils.toJson

class UserAnnotationControllerV1Test {

    private val userAnnotationService = mockk<UserAnnotationFacade>()
    private val controller = UserAnnotationControllerV1(userAnnotationService)
    private val mockMvc = standaloneSetup(controller).build()

    @AfterEach
    fun tearDown() {
        clearMocks(userAnnotationService)
    }

    @Test
    fun `Update user annotations`() {
        val jsonEntries = """{"key": "value"}"""
        every {
            userAnnotationService.updateAnnotations(
                "filters",
                jsonEntries.toJson()
            )
        } returns mapOf("filters" to jsonEntries.toJson())

        val response = mockMvc.perform(
            patch("/v1/users/annotations/{key}", "filters")
                .contentType(MediaType.APPLICATION_JSON_UTF8)
                .content(jsonEntries)
        )

        response.andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].filters.key").value("value"))
    }

    @Test
    fun `Get all user annotations`() {
        every { userAnnotationService.getAnnotations() } returns mapOf(
            "filters" to """{"key":"first"}""".toJson(),
            "test-key" to """{"key":"second"}""".toJson()
        )

        val response = mockMvc.perform(get("/v1/users/annotations"))

        response.andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].filters.key").value("first"))
            .andExpect(jsonPath("$.items[1].test-key.key").value("second"))
    }

    @Test
    fun `Get user annotations by key`() {
        every { userAnnotationService.getAnnotations() } returns mapOf(
            "filters" to """{"key":"first"}""".toJson(),
            "test-key" to """{"key":"second"}""".toJson()
        )

        val response = mockMvc.perform(get("/v1/users/annotations/{key}", "filters"))

        response.andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].filters.key").value("first"))
    }

    @Test
    fun `Delete user annotation`() {
        every { userAnnotationService.deleteAnnotations("filters") } returns mapOf("key" to TextNode("value"))

        val response = mockMvc.perform(delete("/v1/users/annotations/{key}", "filters"))

        response.andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.items[0].key").value("value"))
    }
}
