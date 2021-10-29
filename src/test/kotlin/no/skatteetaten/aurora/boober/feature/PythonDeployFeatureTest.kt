package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.isEqualTo
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeployment
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import org.junit.jupiter.api.Test

class PythonDeployFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = PythonDeployFeature("docker.registry")

    @Test
    fun `should generate resource for python application`() {
        val (adResource, dcResource, serviceResource, isResource) = generateResources(
            """{
          "version" : "1",
          "groupId" : "org.test",
          "applicationPlatform" : "python"
      }""",
            resource = createEmptyApplicationDeployment(),
            createdResources = 3
        )

        assertThat(adResource).auroraResourceModifiedByThisFeatureWithComment("Added application name and id")
        val ad = adResource.resource as ApplicationDeployment
        assertThat(ad.spec.applicationId).isEqualTo("665c8f8518c18e8fc6b28a458496ce19bf9e7645")
        assertThat(ad.spec.applicationName).isEqualTo("simple")

        assertThat(dcResource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("dc.json")
        assertThat(serviceResource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("service.json")
        assertThat(isResource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("is.json")
    }
}
