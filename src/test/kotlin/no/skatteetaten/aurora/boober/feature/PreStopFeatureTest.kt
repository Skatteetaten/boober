package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.isSuccess
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.singleApplicationError
import org.junit.jupiter.api.Test

class PreStopFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = PreStopFeature()

    @Test
    fun `should succeed with duration set`() {
        assertThat {
            createAuroraConfigFieldHandlers(
                """{ 
                    "lifecycle" : {
                      "preStopDuration" : "10s"
                    }
               }"""
            )
        }.isSuccess()
    }

    @Test
    fun `should not allow wrong duration string`() {
        assertThat {
            createAuroraConfigFieldHandlers(
                """{ 
                     "lifecycle" : {
                      "preStopDuration" : "asd"
                    }
                    
               }"""
            )
        }.singleApplicationError("'asd' is not a valid simple duration")
    }

    @Test
    fun `modify dc and add container preStop`() {

        val (dcResource) = modifyResources(
            """{ 
               "lifecycle" : {
                      "preStopDuration" : "10s"
                    }
           }""", createEmptyDeploymentConfig()
        )

        assertThat(dcResource).auroraResourceModifiedByThisFeatureWithComment("Added preStop exec")

        assertThat(dcResource).auroraResourceMatchesFile("dc.json")
    }
}
