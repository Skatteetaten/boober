package no.skatteetaten.aurora.boober.service

import assertk.assert
import assertk.assertions.isEqualTo
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.fge.jsonpatch.JsonPatch
import io.mockk.mockk
import org.junit.jupiter.api.Test

class UserAnnotationServiceTest {

    private val userAnnotationService = UserAnnotationService(mockk())

    @Test
    fun `Given filters map create valid json patch for user annotations`() {
        val json = userAnnotationService.createJsonPatch("filters", mapOf("key1" to "value1"))
        val jsonPatch = jacksonObjectMapper().readValue<JsonPatch>(json)

        val userResource = """{ "metadata": { "annotations": {} } }"""
        val patchedJson = jsonPatch.apply(jacksonObjectMapper().readValue(userResource))

        assert(patchedJson.at("/metadata/annotations/filters/key1").textValue()).isEqualTo("value1")
    }
}