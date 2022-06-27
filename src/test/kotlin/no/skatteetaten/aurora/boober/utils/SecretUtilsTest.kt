package no.skatteetaten.aurora.boober.utils

import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test
import java.util.Base64

internal class SecretUtilsTest {

    @Test
    fun `editEncodedValue should change a Base64 encoded value with a given key in a map of secrets`() {

        val secretMap = mapOf(
            "testKey1" to "change this value",
            "testKey2" to "edit this value with some function",
            "testKey3" to "keep this value"
        ).mapValues { Base64.getEncoder().encodeToString(it.value.toByteArray()) }.toMutableMap()

        secretMap.editEncodedValue("testKey1") { "another value" }
        secretMap.editEncodedValue("testKey2") { it.uppercase() }

        assertThatBase64Decoded(secretMap["testKey1"]).isEqualTo("another value")
        assertThatBase64Decoded(secretMap["testKey2"]).isEqualTo("EDIT THIS VALUE WITH SOME FUNCTION")
        assertThatBase64Decoded(secretMap["testKey3"]).isEqualTo("keep this value")
    }
}
