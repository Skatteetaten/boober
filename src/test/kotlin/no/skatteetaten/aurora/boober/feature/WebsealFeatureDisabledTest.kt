package no.skatteetaten.aurora.boober.feature

import org.junit.jupiter.api.Test
import assertk.assertThat
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.singleApplicationError

class WebsealFeatureDisabledTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = WebsealDisabledFeature()

    @Test
    fun `get error if trying to create webseal opening when feature disabled`() {

        assertThat {
            generateResources(
                """{ 
                "webseal" : true
           }"""
            )
        }.singleApplicationError("Webseal is not supported")
    }
}
