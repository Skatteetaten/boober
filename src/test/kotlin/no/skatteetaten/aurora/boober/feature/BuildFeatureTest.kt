package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import io.fabric8.openshift.api.model.DeploymentConfig
import io.fabric8.openshift.api.model.ImageStream
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import org.apache.commons.lang3.RandomStringUtils
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
    fun `should get error if groupId is missing`() {

        assertThat {
            createAuroraDeploymentContext(
                """{
           "type": "development", 
           "version" : "1"
        }"""
            )
        }.singleApplicationError("GroupId must be set and be shorter then 200 characters.")
    }

    @Test
    fun `should get error if groupId is too long`() {

        val groupId = RandomStringUtils.randomAlphanumeric(201)
        assertThat {
            createAuroraDeploymentContext(
                """{
           "type": "development", 
           "version" : "1",
           "groupId" : "$groupId"
        }"""
            )
        }.singleApplicationError("GroupId must be set and be shorter then 200 characters.")
    }

    // TODO: Where should we test gav handlers? I would say in a seperate test or?
    @Test
    fun `should get error if version is not set`() {

        assertThat {
            createAuroraDeploymentContext(
                """{
           "type": "development", 
           "groupId" : "org.test"
        }"""
            )
        }.singleApplicationError("Version must be a 128 characters or less, alphanumeric and can contain dots and dashes.")
    }

    @Test
    fun `should generate build and modify imageStream with default values`() {

        val (dcResources, isResource, bcResource) = generateResources(
            """{
           "type": "development", 
           "groupId": "org.test",
           "version" : "1"
        }""", createEmptyDeploymentConfig(), createEmptyImageStream()
        )

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
