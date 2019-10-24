package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.fkorotkov.kubernetes.newEnvVar
import io.fabric8.openshift.api.model.BuildConfig
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeployment
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import org.junit.jupiter.api.Test

class WebDeployFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = WebDeployFeature("docker.registry")

    @Test
    fun `should generate resource for web application`() {

        val (adResource, bcResource, dcResource, serviceResource, isResource) = generateResources(
            """{
          "version" : "1",
          "groupId" : "org.test",
          "applicationPlatform" : "web"
      }""", resources = mutableSetOf(createEmptyApplicationDeployment(), createEmptyBuildConfig()),
            createdResources = 3
        )

        assertThat(bcResource).auroraResourceModifiedByThisFeatureWithComment("Set applicationType in build")
        val bc = bcResource.resource as BuildConfig
        assertThat(bc.spec.strategy.customStrategy.env.last()).isEqualTo(newEnvVar {
            name = "APPLICATION_TYPE"
            value = "nodejs"
        })

        assertThat(adResource).auroraResourceModifiedByThisFeatureWithComment("Added application name and id")
        val ad = adResource.resource as ApplicationDeployment
        assertThat(ad.spec.applicationId).isEqualTo("665c8f8518c18e8fc6b28a458496ce19bf9e7645")
        assertThat(ad.spec.applicationName).isEqualTo("simple")

        assertThat(dcResource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("dc.json")
        assertThat(serviceResource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("service.json")
        assertThat(isResource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("is.json")
    }
}
