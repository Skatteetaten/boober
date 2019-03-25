package no.skatteetaten.aurora.boober.utils

import assertk.assertThat
import assertk.assertions.containsAll
import assertk.assertions.containsNone
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.Properties

class PropertiesUtilTest {

    val props = """#a text line
    foo=bar
    username=user
    password=pass
    something=sameting
    """.toByteArray()

    @Test
    fun `Remove non-listed keys from properties`() {
        val filteredBytes = filterProperties(props, listOf("username", "password"), emptyMap())

        val filteredProps = loadProperties(filteredBytes)
        assertThat(filteredProps.size).isEqualTo(2)
        val stringNames = filteredProps.stringPropertyNames()
        assertThat(stringNames).containsAll("username", "password")
        assertThat(stringNames).containsNone("foo", "something")
    }

    @Test
    fun `Throw IllegalArgumentException when filtering with a key that is not available in properties`() {
        assertThat {
            filterProperties(props, listOf("username", "unknown"), null)
        }.thrownError { isInstanceOf(IllegalArgumentException::class) }
    }

    @Test
    fun `Replace filtered key mappings`() {
        val filteredBytes = filterProperties(props, listOf("username"), mapOf("username" to "new-username"))
        val filteredProps = loadProperties(filteredBytes)
        assertThat(filteredProps.getProperty("new-username")).isEqualTo("user")
        assertThat(filteredProps.size).isEqualTo(1)
    }

    @Test
    fun `Replace key mappings without filtering keys`() {
        val filteredBytes = filterProperties(props, listOf(), mapOf("username" to "new-username"))

        val filteredProps = loadProperties(filteredBytes)

        assertThat(filteredProps.getProperty("new-username")).isEqualTo("user")
        assertThat(filteredProps.size).isEqualTo(4)
    }

    fun loadProperties(bytes: ByteArray): Properties {
        val filteredProps = Properties()
        filteredProps.load(ByteArrayInputStream(bytes))
        return filteredProps
    }
}
