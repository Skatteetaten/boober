package no.skatteetaten.aurora.boober.unit

import org.junit.jupiter.api.Test
import assertk.assertThat
import assertk.assertions.isFailure
import assertk.assertions.messageContains
import no.skatteetaten.aurora.boober.feature.FeatureContext
import no.skatteetaten.aurora.boober.feature.getContextKey

class FeatureContextTest {

    @Test
    fun `should get good error message when context name is wrong`() {

        val context: FeatureContext = mapOf("foo" to "bar")
        assertThat {
            context.getContextKey<String>("baz")
        }.isFailure().messageContains("The feature context key=baz was not found in the context. keys=[foo]")
    }

    @Test
    fun `should get good error message when context type  is wrong`() {

        val context: FeatureContext = mapOf("foo" to "bar")
        assertThat {
            context.getContextKey<Int>("foo")
        }.isFailure()
            .messageContains("The feature context key=foo was not the expected type class java.lang.String cannot be cast to class java.lang.Integer")
    }
}
