package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.isEqualTo
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import org.junit.jupiter.api.Test

class BuildFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = BuildFeature()

    @Test
    fun `should disable feature if deploy type`() {

        val adc = createAuroraDeploymentContext()
        assertThat(adc.features.isEmpty())
    }

    @Test
    fun `should get default handlers`() {

        val adc = createAuroraConfigFieldHandlers(
            """{
           "type": "development", 
           "groupId": "org.test",
           "version" : "1"
        }"""
        )

        val handlers = adc.map { it.name }
        assertThat(adc.size).isEqualTo(22)
    }
}