package no.skatteetaten.aurora.boober.controller.v1

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.service.UserAnnotationService
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
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
    fun `Add user annotation`() {
        val jsonEntries = """{"key1": ["value1", "value2"]}"""
        val entries = jacksonObjectMapper().readValue<Map<String, Any>>(jsonEntries)

        every { userAnnotationService.addAnnotation("myusername", "filters", entries) } returns
            OpenShiftResponse(OpenshiftCommand(OperationType.UPDATE))

        val response = mockMvc.perform(
            patch("/v1/users/{user}/annotations/{key}", "myusername", "filters")
                .contentType(MediaType.APPLICATION_JSON_UTF8)
                .content(jsonEntries)
        )

        response.andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].success").value(true))
    }
}