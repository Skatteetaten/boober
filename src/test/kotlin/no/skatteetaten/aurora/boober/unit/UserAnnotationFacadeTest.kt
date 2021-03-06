package no.skatteetaten.aurora.boober.unit

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import assertk.assertions.startsWith
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.facade.UserAnnotationFacade
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.utils.base64Prefix
import no.skatteetaten.aurora.boober.utils.toBase64
import no.skatteetaten.aurora.boober.utils.toJson
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.ResponseEntity

class UserAnnotationFacadeTest {

    private val userDetailsProvider = mockk<UserDetailsProvider>().apply {
        every { getAuthenticatedUser() } returns User("username", "token")
    }
    private val openShiftResourceClient = mockk<OpenShiftResourceClient>()
    private val facade = UserAnnotationFacade(userDetailsProvider, openShiftResourceClient)

    @AfterEach
    fun tearDown() {
        clearMocks(openShiftResourceClient)
    }

    @Test
    fun `Given filters map return valid json patch for adding user annotations`() {
        val json = facade.createUpdatePatch("filters", """{"key":{"nested-key":"value"}}""".toJson())
        assertThat(json.at("/metadata/annotations/filters").textValue()).startsWith(base64Prefix)
    }

    @Test
    fun `Given key create valid json patch for removing user annotations`() {
        val json = facade.createRemovePatch("filters")
        assertThat(json.at("/metadata/annotations/filters").isNull).isTrue()
    }

    @Test
    fun `Get user annotations where value is base64 encoded and json`() {
        val entry = """{"key1":"value1"}""".toBase64()

        every {
            openShiftResourceClient.get("user", name = "username")
        } returns ResponseEntity.ok("""{"metadata":{"annotations":{"key":"$entry"}}}""".toJson())

        val response = facade.getAnnotations()
        assertThat(response["key"]?.at("/key1")?.textValue()).isEqualTo("value1")
    }

    @Test
    fun `Get user annotations where value is base64 encoded and text`() {
        val entry = """test value""".toBase64()

        every {
            openShiftResourceClient.get("user", name = "username")
        } returns ResponseEntity.ok("""{"metadata":{"annotations":{"key":"$entry"}}}""".toJson())

        val response = facade.getAnnotations()
        assertThat(response["key"]?.textValue()).isEqualTo("test value")
    }

    @Test
    fun `Get user annotation where value is plain text`() {
        every {
            openShiftResourceClient.get("user", name = "username")
        } returns ResponseEntity.ok("""{"metadata":{"annotations":{"key":"value"}}}""".toJson())

        val response = facade.getAnnotations()
        assertThat(response["key"]?.textValue()).isEqualTo("value")
    }

    @Test
    fun `Delete user annotations return existing annotations`() {
        every {
            openShiftResourceClient.strategicMergePatch("user", "username", any())
        } returns ResponseEntity.ok("""{"metadata":{"annotations":{"key":"value"}}}""".toJson())

        val response = facade.deleteAnnotations("filters")
        assertThat(response["key"]?.textValue()).isEqualTo("value")
    }

    @Test
    fun `Delete user annotations return no annotations`() {
        every {
            openShiftResourceClient.strategicMergePatch("user", "username", any())
        } returns ResponseEntity.ok("""{}""".toJson())

        val response = facade.deleteAnnotations("filters")
        assertThat(response.isEmpty()).isTrue()
    }
}
