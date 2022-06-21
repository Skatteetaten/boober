package no.skatteetaten.aurora.boober.utils

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

internal class CollectionUtilsTest {

    @Test
    fun `countSetValues should return the number of values that are not null, false or blank`() {
        assertThat(countSetValues("", " ", null, false)).isEqualTo(0)
        assertThat(countSetValues("", "a", " ", null, false)).isEqualTo(1)
        assertThat(countSetValues("", "a", " ", null, true, false)).isEqualTo(2)
        assertThat(countSetValues("a", true)).isEqualTo(2)
    }
}
