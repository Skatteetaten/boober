package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.singleApplicationError
import org.junit.jupiter.api.Test

class StsFeatureDisabledTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = StsDisabledFeature()

    @Test
    fun `get error if trying to create certificate when feature disabled`() {

        assertThat {
            generateResources(
                """{ 
               "sts" : true,
               "groupId" : "org.test"
           }"""
            )
        }.singleApplicationError("STS is not supported")
    }
}
