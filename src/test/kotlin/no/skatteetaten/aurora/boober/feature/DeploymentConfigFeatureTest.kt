package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeployment
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import org.junit.jupiter.api.Test

// TODO: Test handlers and override files
class DeploymentConfigFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = DeploymentConfigFeature()

    @Test
    fun `modify dc and ad for default parameters`() {

        val resources = generateResources(
            """{ 
                "version" : "1"
           }""", createEmptyDeploymentConfig(), createEmptyApplicationDeployment()
        )

        assertThat(resources.size).isEqualTo(2)
        val dcResource = resources.first()
        val adResource = resources.last()

        assertThat(adResource).auroraResourceModifiedByThisFeatureWithComment("Added information from deployment")
        assertThat(dcResource).auroraResourceModifiedByThisFeatureWithComment("Added labels, annotations, shared env vars and request limits")

        val ad = adResource.resource as ApplicationDeployment
        assertThat(ad.spec.deployTag).isEqualTo("1")
        assertThat(ad.spec.managementPath).isEqualTo(":8081/actuator")
        assertThat(ad.spec.splunkIndex).isNull()
        assertThat(ad.spec.releaseTo).isNull()

        assertThat(dcResource).auroraResourceMatchesFile("default-dc.json")
    }

    @Test
    fun `modify dc and ad for changed parameters`() {

        val resources = generateResources(
            app = """{ 
                
                "version" : "1",
                "releaseTo" : "test", 
                "splunkIndex" : "test",
                "debug" : true
                
           }""",
            resources = mutableSetOf(createEmptyDeploymentConfig(), createEmptyApplicationDeployment()),
            files = listOf(AuroraConfigFile("simple.json", """{ "pause" : true }""", override = true))
        )

        assertThat(resources.size).isEqualTo(2)
        val dcResource = resources.first()
        val adResource = resources.last()

        assertThat(adResource).auroraResourceModifiedByThisFeatureWithComment("Added information from deployment")
        assertThat(dcResource).auroraResourceModifiedByThisFeatureWithComment("Added labels, annotations, shared env vars and request limits")

        val ad = adResource.resource as ApplicationDeployment
        assertThat(ad.spec.deployTag).isEqualTo("test")
        assertThat(ad.spec.managementPath).isEqualTo(":8081/actuator")
        assertThat(ad.spec.splunkIndex).isEqualTo("test")
        assertThat(ad.spec.releaseTo).isEqualTo("test")

        assertThat(dcResource).auroraResourceMatchesFile("changed-dc.json")
    }
}
