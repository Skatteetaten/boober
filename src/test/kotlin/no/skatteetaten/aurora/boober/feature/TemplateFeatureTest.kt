package no.skatteetaten.aurora.boober.feature

import org.junit.jupiter.api.Test
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeployment
import no.skatteetaten.aurora.boober.service.AuroraTemplateService
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.singleApplicationErrorResult

class TemplateFeatureTest : AbstractFeatureTest() {

    val templateService: AuroraTemplateService = mockk()

    override val feature: Feature
        get() = TemplateFeature(templateService, "utv")

    val template: String = this.javaClass.getResource("/samples/config/templates/atomhopper.json").readText()

    @Test
    fun `should allow template with prometheus handlers`() {
        every { templateService.findTemplate("atomhopper") } returns jacksonObjectMapper().readTree(template)

        val (validSimplePrometheus, invalidSimplePrometheus) =
            createAuroraDeploymentContext(
                """{
                "type" : "template",
                "template" : "atomhopper",
                "parameters" : {
                  "FEED_NAME" : "simple", 
                  "DB_NAME" : "simple", 
                  "DOMAIN_NAME" : "simple"
                 },
                "prometheus": false,
                "version": "0"
        }"""
            )

        assertThat(validSimplePrometheus).isNotEmpty()
        assertThat(invalidSimplePrometheus).isEmpty()

        val (validComplexPrometheus, invalidComplexPrometheus) = createAuroraDeploymentContext(
            """{
                "type" : "template",
                "template" : "atomhopper",
                "parameters" : {
                  "FEED_NAME" : "simple", 
                  "DB_NAME" : "simple", 
                  "DOMAIN_NAME" : "simple"
                 },
                "prometheus": {
                    "path": "none",
                    "port": "anything"
                },
                "version": "0"
            }"""
        )

        assertThat(validComplexPrometheus).isNotEmpty()
        assertThat(invalidComplexPrometheus).isEmpty()
    }

    @Test
    fun `should get error if template is missing`() {

        assertThat {
            createAuroraDeploymentContext(
                """{ 
            "type" : "template",
            "version": "0"
            }"""
            )
        }.singleApplicationErrorResult("Template is required")
    }

    @Test
    fun `should get error when version is missing`() {

        every { templateService.findTemplate("atomhopper") } returns jacksonObjectMapper().readTree(template)

        assertThat {
            createAuroraDeploymentContext(
                """{ 
            "type" : "template", 
            "template" : "atomhopper",
            "parameters" : {
              "FEED_NAME" : "simple", 
              "DB_NAME" : "simple", 
              "DOMAIN_NAME" : "simple"
             }
            }"""
            )
        }.singleApplicationErrorResult("Field is required")
    }

    @Test
    fun `should get error if parameters is missing`() {

        every { templateService.findTemplate("atomhopper") } returns jacksonObjectMapper().readTree(template)

        assertThat {
            createAuroraDeploymentContext(
                """{ 
            "type" : "template",
            "template" : "atomhopper",
            "version": "0"
            }"""
            )
        }.singleApplicationErrorResult("Required template parameters [FEED_NAME, DB_NAME] not set")
    }

    @Test
    fun `should get error if template file is not present in openshift`() {

        every { templateService.findTemplate("atomhopper") } throws IllegalArgumentException("Could not find template=atomhopper")

        assertThat {
            createAuroraDeploymentContext(
                """{ 
            "type" : "template",
            "template" : "atomhopper",
            "version": "0"
            }"""
            )
        }.singleApplicationErrorResult("Could not find template=atomhopper")
    }

    @Test
    fun `should generate resources from template`() {

        every { templateService.findTemplate("atomhopper") } returns jacksonObjectMapper().readTree(template)

        val (adResource, imageStream, service, route, deploymentConfig) = generateResources(
            """{
            "type" : "template", 
            "template" : "atomhopper",
            "parameters" : {
              "FEED_NAME" : "simple", 
              "DB_NAME" : "simple", 
              "DOMAIN_NAME" : "simple"
             },
            "version": "0"
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
