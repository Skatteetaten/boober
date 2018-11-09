package no.skatteetaten.aurora.boober.service

import assertk.assert
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
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
    fun `Given filters map create valid json patch for adding user annotations`() {
        val json = userAnnotationService.createAddPatch("filters", mapOf("key" to mapOf("nested-key" to "value")))
        val jsonPatch = jacksonObjectMapper().treeToValue<JsonPatch>(json)

        val userResource = """{"metadata":{"annotations":{}}}""".toJson()
        val patchedJson = jsonPatch.apply(userResource)

        assert(patchedJson.at("/metadata/annotations/filters").textValue()).isNotNull()
    }

    @Test
    fun `Get user annotations where value is base64 encoded`() {
        val entry = """{"key1":"value1"}"""
        val encodedEntry = Base64Utils.encodeToString(entry.toByteArray())

        every {
            openShiftResourceClient.get("user", "", "username")
        } returns ResponseEntity.ok("""{"metadata":{"annotations":{"key":"${encodedEntry.withBase64Prefix()}"}}}""".toJson())

        val response = userAnnotationService.getAnnotations("filters")
        assert(response["key"]?.at("/key1")?.textValue()).isEqualTo("value1")
    }

    @Test
    fun `Get user annotation where value is plain text`() {
        every {
            openShiftResourceClient.get("user", "", "username")
        } returns ResponseEntity.ok("""{"metadata":{"annotations":{"key":"value"}}}""".toJson())

        val response = userAnnotationService.getAnnotations("filters")
        assert(response["key"]?.textValue()).isEqualTo("value")
    }
}