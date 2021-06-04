package no.skatteetaten.aurora.boober.utils

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class StringUtilsTest {

    @Test
    fun `truncateStringAndHashTrailingCharacters should normalize to 63 chars`() {
        val norm = "dette-er-en-lang-tekst-som-brukes-for-aa-teste-en-funksjon-som-skal-normalisere-tekst".truncateStringAndHashTrailingCharacters(63)
        assertThat(norm).isEqualTo("dette-er-en-lang-tekst-som-brukes-for-aa-teste-en-funks-afb5bf6")
        assertThat(norm.length).isEqualTo(63)
    }

    @Test
    fun `truncateStringAndHashTrailingCharacters should return same string if lenght is shorter than maxLength`() {
        val norm = "test".truncateStringAndHashTrailingCharacters(63)
        assertThat(norm).isEqualTo("test")
        assertThat(norm.length).isEqualTo(4)
    }
}
