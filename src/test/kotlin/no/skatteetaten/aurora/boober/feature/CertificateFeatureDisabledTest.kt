package no.skatteetaten.aurora.boober.feature

import org.junit.jupiter.api.Test
import assertk.assertThat
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.singleApplicationError

class CertificateFeatureDisabledTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = CertificateDisabledFeature()

    @Test
    fun `get error if trying to create certificate when feature disabled`() {

        assertThat {
            generateResources(
                """{ 
               "certificate" : true,
               "groupId" : "org.test"
           }"""
            )
        }.singleApplicationError("STS is not supported")
    }
}
