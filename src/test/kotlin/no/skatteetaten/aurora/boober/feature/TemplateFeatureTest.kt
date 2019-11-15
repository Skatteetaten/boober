package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeployment
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.singleApplicationError
import org.junit.jupiter.api.Test

class TemplateFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = TemplateFeature(openShiftClient, "utv")

    val template: String = this.javaClass.getResource("/samples/config/templates/atomhopper.json").readText()

    @Test
    fun `should get error if template is missing`() {

        assertThat {
            createAuroraDeploymentContext(
                """{ 
            "type" : "template"
            }"""
            )
        }.singleApplicationError("Template is required")
    }

    @Test
    fun `should get error if parameters is missing`() {

        every { openShiftClient.getTemplate("atomhopper") } returns jacksonObjectMapper().readTree(template)

        assertThat {
            createAuroraDeploymentContext(
                """{ 
            "type" : "template",
            "template" : "atomhopper"
            }"""
            )
        }.singleApplicationError("Required template parameters [FEED_NAME, DB_NAME] not set")
    }

    @Test
    fun `should get error if template file is not present in openshift`() {

        every { openShiftClient.getTemplate("atomhopper") } throws IllegalArgumentException("Could not find template")

        assertThat {
            createAuroraDeploymentContext(
                """{ 
            "type" : "template",
            "template" : "atomhopper"
            }"""
            )
        }.singleApplicationError("Could not find template=atomhopper")
        // TODO: Better error message, tell what template is missing
    }

    @Test
    fun `should generate resources from template`() {

        every { openShiftClient.getTemplate("atomhopper") } returns jacksonObjectMapper().readTree(template)

        val (adResource, imageStream, service, route, deploymentConfig) = generateResources(
            """{
            "type" : "template", 
            "template" : "atomhopper",
            "parameters" : {
              "FEED_NAME" : "simple", 
              "DB_NAME" : "simple", 
              "DOMAIN_NAME" : "simple"
             }
           }""",
            resource = createEmptyApplicationDeployment(),
            createdResources = 4
        )

        assertThat(adResource).auroraResourceModifiedByThisFeatureWithComment("Added application name and id")
        val ad = adResource.resource as ApplicationDeployment
        assertThat(ad.spec.applicationId).isEqualTo("e71182e7453ea6592bec2c17c7293b37134606aa")
        assertThat(ad.spec.applicationName).isEqualTo("aurora-atomhopper")

        assertThat(imageStream).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("is.json")
        assertThat(service).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("service.json")
        assertThat(route).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("route.json")
        assertThat(deploymentConfig).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("dc.json")
    }
}
