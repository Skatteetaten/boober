package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.isEqualTo
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeployment
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.singleApplicationError
import org.junit.jupiter.api.Test

class LocalTemplateFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = LocalTemplateFeature("utv")

    val template: String = this.javaClass.getResource("/samples/config/templates/atomhopper.json").readText()
    val templateFile = listOf(AuroraConfigFile("templates/atomhopper.json", contents = template))

    @Test
    fun `should get error if template file is not present`() {

        assertThat {
            createAuroraDeploymentContext(
                """{ 
            "type" : "localTemplate",
            "templateFile" : "templates/atomhopper.json"
            }"""
            )
        }.singleApplicationError("Config for application simple in environment utv contains errors. The file named templates/atomhopper.json does not exist in AuroraConfig.")
    }

    @Test
    fun `should get error if required parameters are not present`() {

        assertThat {
            createAuroraDeploymentContext(
                """{ 
            "type" : "localTemplate", 
            "templateFile" : "templates/atomhopper.json"
            }""", files = templateFile
            )
        }.singleApplicationError("Required template parameters [FEED_NAME, DB_NAME] not set")
    }

    @Test
    fun `should generate resources from template`() {
        val (adResource, imageStream, service, route, deploymentConfig) = generateResources(
            """{
            "type" : "localTemplate", 
            "templateFile" : "templates/atomhopper.json",
            "splunkIndex" : "foo",
            "parameters" : {
              "FEED_NAME" : "simple", 
              "DB_NAME" : "simple"
             }
           }""", files = templateFile,
            resource = createEmptyApplicationDeployment(),
            createdResources = 4
        )

        assertThat(adResource).auroraResourceModifiedByThisFeatureWithComment("Added application name and id")
        val ad = adResource.resource as ApplicationDeployment
        assertThat(ad.spec.applicationId).isEqualTo("81e9586fcc0718312784c585bb94421ee89075ee")
        assertThat(ad.spec.applicationName).isEqualTo("aurora-atomhopper")

        assertThat(imageStream).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("is.json")
        assertThat(service).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("service.json")
        assertThat(route).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("route.json")
        assertThat(deploymentConfig).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("dc.json")
    }
}
