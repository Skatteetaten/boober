package no.skatteetaten.aurora.boober.utils

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class CollectionUtilsTest {

    @Test
    fun `noneAreSet should return true when all inputs are either null, false or blank`() =
        assertTrue(noneAreSet("", " ", null, false))

    @Test
    fun `noneAreSet should return false when at least one boolean is true`() =
        assertFalse(noneAreSet(null, null, true, null))

    @Test
    fun `noneAreSet should return false when at least one string has some content`() =
        assertFalse(noneAreSet(null, "a", null, null))
}
