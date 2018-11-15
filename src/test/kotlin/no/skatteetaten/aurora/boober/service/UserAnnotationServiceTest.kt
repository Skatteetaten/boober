package no.skatteetaten.aurora.boober.service

import assertk.assert
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import assertk.assertions.startsWith
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.utils.base64Prefix
import no.skatteetaten.aurora.boober.utils.toBase64
import no.skatteetaten.aurora.boober.utils.toJson
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.ResponseEntity

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
    fun `Given filters map return valid json patch for adding user annotations`() {
        val json = userAnnotationService.createUpdatePatch("filters", """{"key":{"nested-key":"value"}}""".toJson())
        assert(json.at("/metadata/annotations/filters").textValue()).startsWith(base64Prefix)
    }

    @Test
    fun `Given key create valid json patch for removing user annotations`() {
        val json = userAnnotationService.createRemovePatch("filters")
        assert(json.at("/metadata/annotations/filters").isNull).isTrue()
    }

    @Test
    fun `Get user annotations where value is base64 encoded and json`() {
        val entry = """{"key1":"value1"}""".toBase64()

        every {
            openShiftResourceClient.get("user", "", "username")
        } returns ResponseEntity.ok("""{"metadata":{"annotations":{"key":"$entry"}}}""".toJson())

        val response = userAnnotationService.getAnnotations()
        assert(response["key"]?.at("/key1")?.textValue()).isEqualTo("value1")
    }

    @Test
    fun `Get user annotations where value is base64 encoded and text`() {
        val entry = """test value""".toBase64()

        every {
            openShiftResourceClient.get("user", "", "username")
        } returns ResponseEntity.ok("""{"metadata":{"annotations":{"key":"$entry"}}}""".toJson())

        val response = userAnnotationService.getAnnotations()
        assert(response["key"]?.textValue()).isEqualTo("test value")
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
    fun `Delete user annotations return existing annotations`() {
        every {
            openShiftResourceClient.strategicMergePatch("user", "username", any())
        } returns ResponseEntity.ok("""{"metadata":{"annotations":{"key":"value"}}}""".toJson())

        val response = userAnnotationService.deleteAnnotations("filters")
        assert(response["key"]?.textValue()).isEqualTo("value")
    }

    @Test
    fun `Delete user annotations return no annotations`() {
        every {
            openShiftResourceClient.strategicMergePatch("user", "username", any())
        } returns ResponseEntity.ok("""{}""".toJson())

        val response = userAnnotationService.deleteAnnotations("filters")
        assert(response.isEmpty()).isTrue()
    }
}