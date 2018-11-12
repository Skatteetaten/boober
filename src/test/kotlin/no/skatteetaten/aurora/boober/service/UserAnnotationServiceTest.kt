package no.skatteetaten.aurora.boober.service

import assertk.assert
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.treeToValue
import com.github.fge.jsonpatch.JsonPatch
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.utils.toJson
import no.skatteetaten.aurora.boober.utils.withBase64Prefix
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.ResponseEntity
import org.springframework.util.Base64Utils

class UserAnnotationServiceTest {

    private val userDetailsProvider = mockk<UserDetailsProvider>().apply {
        every { getAuthenticatedUser() } returns User("username", "token")
    }
    private val openShiftResourceClient = mockk<OpenShiftResourceClient>()
    private val userAnnotationService = UserAnnotationService(userDetailsProvider, openShiftResourceClient)

    @AfterEach
    fun tearDown() {
        clearMocks(openShiftResourceClient)
    }

    @Test
    fun `Given filters map create return valid json patch for adding user annotations`() {
        val json = userAnnotationService.createAddPatch("filters", mapOf("key" to mapOf("nested-key" to "value")))
        val jsonPatch = jacksonObjectMapper().treeToValue<JsonPatch>(json)

        val userResource = """{"metadata":{"annotations":{}}}""".toJson()
        val patchedJson = jsonPatch.apply(userResource)

        assert(patchedJson.at("/metadata/annotations/filters").textValue()).isNotNull()
    }

    @Test
    fun `Given key create valid json patch for removing user annotations`() {
        val json = userAnnotationService.createRemovePatch("filters")
        val jsonPatch = jacksonObjectMapper().treeToValue<JsonPatch>(json)

        val userResource = """{"metadata":{"annotations":{"filters":"test123"}}}""".toJson()
        val patchedJson = jsonPatch.apply(userResource)

        assert(patchedJson.at("/metadata/annotations").isObject).isTrue()
        assert(patchedJson.at("/metadata/annotations/filters").isMissingNode).isTrue()
    }

    @Test
    fun `Get user annotations where value is base64 encoded`() {
        val entry = """{"key1":"value1"}"""
        val encodedEntry = Base64Utils.encodeToString(entry.toByteArray())

        every {
            openShiftResourceClient.get("user", "", "username")
        } returns ResponseEntity.ok("""{"metadata":{"annotations":{"key":"${encodedEntry.withBase64Prefix()}"}}}""".toJson())

        val response = userAnnotationService.getAnnotations()
        assert(response["key"]?.at("/key1")?.textValue()).isEqualTo("value1")
    }

    @Test
    fun `Get user annotation where value is plain text`() {
        every {
            openShiftResourceClient.get("user", "", "username")
        } returns ResponseEntity.ok("""{"metadata":{"annotations":{"key":"value"}}}""".toJson())

        val response = userAnnotationService.getAnnotations()
        assert(response["key"]?.textValue()).isEqualTo("value")
    }

    @Test
    fun `Delete user annotations`() {
        every {
            openShiftResourceClient.patch("user", "username", any())
        } returns ResponseEntity.ok("""{"metadata":{"annotations":{"key":"value"}}}""".toJson())

        val response = userAnnotationService.deleteAnnotations("filters")
        assert(response["key"]?.textValue()).isEqualTo("value")
    }
}