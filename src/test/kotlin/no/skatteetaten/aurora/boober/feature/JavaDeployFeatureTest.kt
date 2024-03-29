package no.skatteetaten.aurora.boober.feature

import org.junit.jupiter.api.Test
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isSuccess
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeployment
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.singleApplicationErrorResult

class JavaDeployFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = JavaDeployFeature("docker.registry")

    @Test
    fun `should generate resource for java application`() {

        val (adResource, dcResource, serviceResource, isResource) = generateResources(
            """{
          "version" : "SNAPSHOT-feature_FOO_1001_FIX_STUPID_STUFF_20190402.113042-26-b1.18.1-wingnut8-1.3.0",
          "groupId" : "org.test"
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

    @Test
    fun `should generate resource for java application with adaptions`() {

        val (adResource, dcResource, serviceResource, isResource) = generateResources(
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
      }""",
            resource = createEmptyApplicationDeployment(),
            createdResources = 3
        )

        assertThat(adResource).auroraResourceModifiedByThisFeatureWithComment("Added application name and id")
        val ad = adResource.resource as ApplicationDeployment
        assertThat(ad.spec.applicationId).isEqualTo("665c8f8518c18e8fc6b28a458496ce19bf9e7645")
        assertThat(ad.spec.applicationName).isEqualTo("simple")

        assertThat(dcResource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("dc-expanded.json")
        assertThat(serviceResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("service-no-prometheus.json")
        assertThat(isResource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("is-1.json")
    }

    @Test
    fun `fileName can be long if both artifactId and name exist`() {

        assertThat {
            createCustomAuroraDeploymentContext(
                ApplicationDeploymentRef("utv", "this-name-is-stupid-stupid-stupidly-long-for-no-reason"),
                "about.json" to FEATURE_ABOUT,
                "this-name-is-stupid-stupid-stupidly-long-for-no-reason.json" to """{ "groupId" : "org.test" }""",
                "utv/about.json" to "{}",
                "utv/this-name-is-stupid-stupid-stupidly-long-for-no-reason.json" to
                    """{ "name" : "foo", "artifactId" : "foo", "version" : "1" }"""
            )
        }.isSuccess()
    }

    @Test
    fun `Fails when application name is too long and artifactId blank`() {
        assertThat {
            createCustomAuroraDeploymentContext(
                ApplicationDeploymentRef("utv", "this-name-is-stupid-stupid-stupidly-long-for-no-reason"),
                "about.json" to FEATURE_ABOUT,
                "this-name-is-stupid-stupid-stupidly-long-for-no-reason.json" to """{ "groupId" : "org.test" }""",
                "utv/about.json" to "{}",
                "utv/this-name-is-stupid-stupid-stupidly-long-for-no-reason.json" to
                    """{ "name" : "foo", "version" : "1" }"""
            )
        }.singleApplicationErrorResult("ArtifactId must be set and be shorter then 50 characters")
    }

    @Test
    fun `Fails when envFile does not start with about`() {
        assertThat {
            createAuroraDeploymentContext(
                """{
                "envFile" : "foo.json"
            }"""
            )
        }.singleApplicationErrorResult("envFile must start with about")
    }
}
