package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import io.fabric8.openshift.api.model.DeploymentConfig
import io.fabric8.openshift.api.model.ImageStream
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

    // TODO: How much value is there in this?
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

    @Test
    fun `should generate build and modify imageStream with default values`() {

        val resources = generateResources(
            """{
           "type": "development", 
           "groupId": "org.test",
           "version" : "1"
        }""", createEmptyDeploymentConfig(), createEmptyImageStream()
        )

        assertThat(resources.size).isEqualTo(3)
        val (dcResources, isResource, bcResource) = resources.toList()

        assertThat(bcResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("bc.json")

        val deploymentConfig = dcResources.resource as DeploymentConfig
        val trigger = deploymentConfig.spec.triggers.first()
        assertThat(dcResources).auroraResourceModifiedByThisFeatureWithComment("Change imageChangeTrigger to follow latest")
        assertThat(trigger.type).isEqualTo("ImageChange")
        assertThat(trigger.imageChangeParams.from.name).isEqualTo("simple:latest")

        assertThat(isResource).auroraResourceModifiedByThisFeatureWithComment("Remove spec from imagestream")
        val imageStream = isResource.resource as ImageStream

        assertThat(imageStream.spec).isNull()
    }
}