package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.isEqualTo
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeployment
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import org.junit.jupiter.api.Test

class JavaDeployFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = JavaDeployFeature("docker.registry")

    @Test
    fun `should generate resource for java application`() {

        val resources = generateResources(
            """{
          "version" : "1",
          "groupId" : "org.test"
      }""", resource = createEmptyApplicationDeployment()
        )

        assertThat(resources.size).isEqualTo(4)
        val (adResource, dcResource, serviceResource, isResource) = resources.toList()

        assertThat(adResource).auroraResourceModifiedByThisFeatureWithComment("Added application name and id")
        val ad = adResource.resource as ApplicationDeployment
        assertThat(ad.spec.applicationId).isEqualTo("665c8f8518c18e8fc6b28a458496ce19bf9e7645")
        assertThat(ad.spec.applicationName).isEqualTo("simple")

        assertThat(dcResource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("dc.json")
        assertThat(serviceResource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("service.json")
        assertThat(isResource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("is.json")
    }

    @Test
    fun `should generate resource for java application with adaptions`() {

        val resources = generateResources(
            """{
          "artifactId" : "simple",
          "version" : "1",
          "groupId" : "org.test",
          "readiness" : {
            "path" : "/health"
          },
          "liveness" : true,
          "serviceAccount" : "hero",
          "deployStrategy" : {
            "type" : "recreate"
          },
          "prometheus" : false
      }""", resource = createEmptyApplicationDeployment()
        )

        assertThat(resources.size).isEqualTo(4)
        val (adResource, dcResource, serviceResource, isResource) = resources.toList()

        assertThat(adResource).auroraResourceModifiedByThisFeatureWithComment("Added application name and id")
        val ad = adResource.resource as ApplicationDeployment
        assertThat(ad.spec.applicationId).isEqualTo("665c8f8518c18e8fc6b28a458496ce19bf9e7645")
        assertThat(ad.spec.applicationName).isEqualTo("simple")

        assertThat(dcResource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("dc-expanded.json")
        assertThat(serviceResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("service-no-prometheus.json")
        assertThat(isResource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("is.json")
    }
}
