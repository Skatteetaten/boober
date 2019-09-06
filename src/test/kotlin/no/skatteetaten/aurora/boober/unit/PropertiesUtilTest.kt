package no.skatteetaten.aurora.boober.unit

import assertk.all
import assertk.assertThat
import assertk.assertions.containsAll
import assertk.assertions.containsNone
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import no.skatteetaten.aurora.boober.utils.filterProperties
import org.junit.jupiter.api.Test

class PropertiesUtilTest {

    val props = """#a text line
    foo=bar
    username=user
    password=pass
    something=sameting
    """.toByteArray()

    @Test
    fun `Remove non-listed keys from properties`() {
        val filteredProps =
            filterProperties(props, listOf("username", "password"), emptyMap())

        assertThat(filteredProps.size).isEqualTo(2)
        val stringNames = filteredProps.stringPropertyNames()
        assertThat(stringNames).containsAll("username", "password")
        assertThat(stringNames).containsNone("foo", "something")
    }

    @Test
    fun `Throw IllegalArgumentException when filtering with a key that is not available in properties`() {
        assertThat {
            filterProperties(props, listOf("username", "unknown"), null)
        }.isFailure().all { isInstanceOf(IllegalArgumentException::class) }
    }

    @Test
    fun `Replace filtered key mappings`() {
        val filteredProps = filterProperties(
            props,
            listOf("username"),
            mapOf("username" to "new-username")
        )
        assertThat(filteredProps.getProperty("new-username")).isEqualTo("user")
        assertThat(filteredProps.size).isEqualTo(1)
    }

    @Test
    fun `Replace key mappings without filtering keys`() {
        val filteredProps =
            filterProperties(props, listOf(), mapOf("username" to "new-username"))

        assertThat(filteredProps.getProperty("new-username")).isEqualTo("user")
        assertThat(filteredProps.size).isEqualTo(4)
    }
}
