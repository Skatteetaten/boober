package no.skatteetaten.aurora.boober.utils

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.fasterxml.jackson.databind.node.TextNode
import org.junit.jupiter.api.Test

class JsonNodeUtilsTest {

    @Test
    fun `startsWith with required prefix should return null`() {
        val exception = TextNode.valueOf("/success").startsWith("/", "not used")
        assertThat(exception).isNull()
    }

    @Test
    fun `startsWith without required prefix should return exception`() {
        val exception = TextNode.valueOf("failure").startsWith("/", "some message")
        assertThat(exception).isNotNull()
        assertThat(exception?.javaClass).isEqualTo(IllegalArgumentException::class.java)
        assertThat(exception?.message).isEqualTo("some message")
    }

    @Test
    fun `notEndsWith with allowed postfix should return null`() {
        val exception = TextNode.valueOf("success").notEndsWith("/", "not used")
        assertThat(exception).isNull()
    }

    @Test
    fun `notEndsWith with not allowed postfix should return exception`() {
        val exception = TextNode.valueOf("failure/").notEndsWith("/", "some message")
        assertThat(exception).isNotNull()
        assertThat(exception?.javaClass).isEqualTo(IllegalArgumentException::class.java)
        assertThat(exception?.message).isEqualTo("some message")
    }

    @Test
    fun `startsWith should return null when null and not required`() {
        val exception = TextNode.valueOf(null).startsWith("/", "some message", required = false)
        assertThat(exception).isNull()
    }

    @Test
    fun `validUrl with https required should return exception when https is missing`() {
        val exception = TextNode.valueOf("http://insecure.url").validUrl(requireHttps = true)
        assertThat(exception).isNotNull()
        assertThat(exception?.javaClass).isEqualTo(IllegalArgumentException::class.java)
        assertThat(exception?.message).isEqualTo("URL should start with https://")
    }

    @Test
    fun `validUrl should return exception when not an url`() {
        val exception = TextNode.valueOf("not a valid url").validUrl()
        assertThat(exception).isNotNull()
        assertThat(exception?.javaClass).isEqualTo(IllegalArgumentException::class.java)
        assertThat(exception?.message).isEqualTo("Illegal character in path at index 3: not a valid url")
    }

    @Test
    fun `validUrl with https not required should return null when https is present`() {
        val exception = TextNode.valueOf("https://secure.url").validUrl(requireHttps = true)
        assertThat(exception).isNull()
    }

    @Test
    fun `versionPattern should return exception when not a valid version`() {
        val exception = TextNode.valueOf("not a version").versionPattern()
        assertThat(exception).isNotNull()
        assertThat(exception?.javaClass).isEqualTo(IllegalArgumentException::class.java)
        assertThat(exception?.message).isEqualTo("Please specify version with vX. Examples v1, v2 etc.")
    }

    @Test
    fun `versionPattern should return null with a valid version`() {
        val exception = TextNode.valueOf("v123").versionPattern()
        assertThat(exception).isNull()
    }
}
