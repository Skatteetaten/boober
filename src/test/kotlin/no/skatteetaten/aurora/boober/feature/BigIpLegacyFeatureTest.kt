package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import no.skatteetaten.aurora.boober.model.openshift.BigIp
import no.skatteetaten.aurora.boober.model.openshift.BigIpSpec
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.singleApplicationError
import org.junit.jupiter.api.Test

class BigIpLegacyFeatureTest : AbstractFeatureTest() {
    private val routeSuffix = ".test.foo"
    override val feature: Feature
        get() = BigIpLegacyFeature(routeSuffix, BigIpFeature(routeSuffix))

    @Test
    fun `should get error if bigip without servicename`() {
        assertThat {
            createAuroraDeploymentContext(
                """{
                 "bigip" : {
                   "asmPolicy" : "public"
                 }
               }"""
            )
        }.singleApplicationError(BigIpFeature.Errors.MissingLegacyService.message)
    }

    @Test
    fun `should not generate big ip crd and route if enabled is false`() {
        val generatedResource = generateResources(
            """{
             "bigip" : {
                  "service": "simple", 
                  "routeAnnotations" : {
                    "haproxy.router.openshift.io|timeout" : "30s"
                   },
                   "enabled": false
             }
           }"""
        )

        assertThat(generatedResource).isEmpty()
    }

    @Test
    fun `should generate big ip crd and route`() {

        val (routeResource, bigIpResource) = generateResources(
            """{
            "bigip" : {
              "service" : "simple",
              "routeAnnotations" : {
                "haproxy.router.openshift.io|timeout" : "30s"
               }
            }
        }""", createdResources = 2
        )

        assertThat(routeResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("route.json")

        assertThat(bigIpResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("bigip.json")

        val bigIpSpec: BigIpSpec = (bigIpResource.resource as BigIp).spec
        assertThat(routeResource.resource.metadata.name).isEqualTo(bigIpSpec.routeName)
    }

    @Test
    fun `test render spec for bigip`() {

        val spec = createAuroraDeploymentSpecForFeature(
            """{
            "bigip" : {
              "service" : "simple",
              "routeAnnotations" : {
                "haproxy.router.openshift.io|timeout" : "30s"
               }
            }
        }"""
        )

        assertThat(spec).auroraDeploymentSpecMatches("spec-default.json")
    }
}
