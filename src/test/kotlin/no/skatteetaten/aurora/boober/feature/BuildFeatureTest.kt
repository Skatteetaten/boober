package no.skatteetaten.aurora.boober.feature

import org.apache.commons.lang3.RandomStringUtils
import org.junit.jupiter.api.Test
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import io.fabric8.openshift.api.model.DeploymentConfig
import io.fabric8.openshift.api.model.ImageStream
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.singleApplicationErrorResult

class BuildFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = BuildFeature("test.docker.com", "1")

    @Test
    fun `should disable feature if deploy type`() {

        val (valid, _) = createAuroraDeploymentContext()
        assertThat(valid.first().features.isEmpty())
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
        }.singleApplicationErrorResult("GroupId must be set and be shorter then 200 characters.")
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
        }.singleApplicationErrorResult("GroupId must be set and be shorter then 200 characters.")
    }

    @Test
    fun `should get error if version is not set`() {

        assertThat {
            createAuroraDeploymentContext(
                """{
           "type": "development", 
           "groupId" : "org.test"
        }"""
            )
        }.singleApplicationErrorResult("Field is required")
    }

    @Test
    fun `should generate build and modify imageStream with default values`() {

        val (dcResources, isResource, bcResource) = generateResources(
            """{
           "type": "development", 
           "groupId": "org.test",
           "version" : "1"
        }""",
            createEmptyDeploymentConfig(), createEmptyImageStream()
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

    @Test
    fun `test render spec for minimal build`() {

        val spec = createAuroraDeploymentSpecForFeature(
            """{
           "type": "development", 
           "groupId": "org.test",
           "version" : "1"
        }"""
        )

        assertThat(spec).auroraDeploymentSpecMatches("spec-default.json")
    }
}
