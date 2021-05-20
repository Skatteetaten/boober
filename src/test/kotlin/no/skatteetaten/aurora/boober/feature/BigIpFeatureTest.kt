package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import no.skatteetaten.aurora.boober.model.openshift.BigIp
import no.skatteetaten.aurora.boober.model.openshift.BigIpSpec
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.singleApplicationError
import org.junit.jupiter.api.Test

class BigIpFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = BigIpFeature(".test.foo")

    @Test
    fun `should get error if bigip without servicename`() {
        assertThat {
            createAuroraDeploymentContext(
                """{
                 "bigip" : {
                   "simple" : {
                     "asmPolicy" : "public"
                   }
                 }
               }"""
            )
        }.singleApplicationError(BigIpFeature.Errors.MissingMultipleService.message)
    }

    @Test
    fun `should get error if bigip legacy config and multiple config is set`() {
        assertThat {
            createAuroraDeploymentContext(
                """{
                 "bigip" : {
                   "service": "legacy",
                   "simple" : {
                     "service": "simple"
                   }
                 }
               }"""
            )
        }.singleApplicationError(BigIpFeature.Errors.BothLegacyAndMultipleConfigIsSet.message)
    }

    @Test
    fun `should not generate big ip crd and route if enabled is false`() {
        val generatedResource = generateResources(
            """{
             "bigip" : {
               "simple": {
                 "service": "simple", 
                 "routeAnnotations" : {
                   "haproxy.router.openshift.io|timeout" : "30s"
                 },
                 "enabled": false
               }
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
              "simple": {
                "service" : "simple",
                "routeAnnotations" : {
                  "haproxy.router.openshift.io|timeout" : "30s"
                }
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
              "simple": {
                "service" : "simple",
                "routeAnnotations" : {
                  "haproxy.router.openshift.io|timeout" : "30s"
                }
              }
            }
        }"""
        )

        assertThat(spec).auroraDeploymentSpecMatches("spec-default.json")
    }

    @Test
    fun `should generate several big ip cr and route`() {

        val resources = generateResources(
            """
        {
           "bigip" : {
             "simple": {
               "service": "simple",
                "routeAnnotations" : {
                  "haproxy.router.openshift.io|timeout" : "30s"
                 }
             },
             "simple-2": {
               "service": "simple-2",
                "routeAnnotations" : {
                  "haproxy.router.openshift.io|timeout" : "30s"
                 }
             }
           }
        }
        """, createdResources = 4
        )

        val bigIpSpec1: BigIpSpec = (resources[1].resource as BigIp).spec
        assertThat(resources[0].resource.metadata.name).isEqualTo(bigIpSpec1.routeName)

        val bigIpSpec2: BigIpSpec = (resources[3].resource as BigIp).spec
        assertThat(resources[2].resource.metadata.name).isEqualTo(bigIpSpec2.routeName)

        assertThat(resources[0]).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("MultipleRoutes/route-1.json")

        assertThat(resources[1]).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("MultipleRoutes/bigip-1.json")

        assertThat(resources[2]).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("MultipleRoutes/route-2.json")

        assertThat(resources[3]).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("MultipleRoutes/bigip-2.json")
    }

    @Test
    fun `should only generate simple-2 route when simple is disabled`() {

        val resources = generateResources(
            """
        {
           "bigip" : {
             "simple": {
               "enabled": false,
               "service": "simple",
                "routeAnnotations" : {
                  "haproxy.router.openshift.io|timeout" : "30s"
                 }
             },
             "simple-2": {
               "service": "simple-2",
                "routeAnnotations" : {
                  "haproxy.router.openshift.io|timeout" : "30s"
                 }
             }
           }
        }
        """, createdResources = 2
        )

        val bigIpSpec1: BigIpSpec = (resources[1].resource as BigIp).spec
        assertThat(resources[0].resource.metadata.name).isEqualTo(bigIpSpec1.routeName)

        assertThat(resources[0]).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("MultipleRoutes/route-2.json")

        assertThat(resources[1]).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("MultipleRoutes/bigip-2.json")
    }

    // LEGACY CONFIG
    @Test
    fun `Legacy - should get error if bigip without servicename`() {
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
    fun `Legacy - should not generate big ip crd and route if enabled is false`() {
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
    fun `Legacy - should generate big ip crd and route`() {

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
    fun `Legacy - test render spec for bigip`() {

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

        assertThat(spec).auroraDeploymentSpecMatches("legacy-spec-default.json")
    }
}
