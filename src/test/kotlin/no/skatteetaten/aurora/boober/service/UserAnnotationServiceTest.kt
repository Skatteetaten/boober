package no.skatteetaten.aurora.boober.service

import assertk.assert
import assertk.assertions.isNotNull
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.treeToValue
import com.github.fge.jsonpatch.JsonPatch
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.utils.toJson
import org.junit.jupiter.api.Test

class UserAnnotationServiceTest {

    private val userDetailsProvider = mockk<UserDetailsProvider>().apply {
        every { getAuthenticatedUser() } returns User("username", "token")
    }
    private val userAnnotationService = UserAnnotationService(userDetailsProvider, mockk())

    @Test
    fun `Given filters map create valid json patch for adding user annotations`() {
        val json = userAnnotationService.createAddPatch("filters", mapOf("key" to mapOf("nested-key" to "value")))
        val jsonPatch = jacksonObjectMapper().treeToValue<JsonPatch>(json)

        val userResource = """{ "metadata": { "annotations": {} } }""".toJson()
        val patchedJson = jsonPatch.apply(userResource)

        assert(patchedJson.at("/metadata/annotations/filters").textValue()).isNotNull()
    }
}