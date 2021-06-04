package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import io.fabric8.openshift.api.model.Route
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
                "enabled": true,
                "service" : "simple",
                "asmPolicy": "something",
                "externalHost": "localhost",
                "oauthScopes": "test",
                "apiPaths": "/api/simple/,/web/simple/",
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
    fun `should truncate route if length is over 63 char`() {

        val (route) = generateResources(
            """
        {
            "name": "etveldiglangtapplikasjonsnavn",
            "bigip" : {
                "etveldiglangtbigipkonfignavn": {
                    "service": "simple"
                }
            }
        }
        """, createdResources = 2
        )

        val routeSpec = (route.resource as Route).spec
        assertThat(routeSpec.host).isEqualTo("bigip-etveldiglangtapplikasjonsnavn-paas-utv-etveldigla-fc9684a.test.foo")
    }

    @Test
    fun `should generate several big ip cr and route`() {

        val (route1, bigip1, route2, bigip2) = generateResources(
            """
        {
           "bigip" : {
             "simple": {
               "service": "simple",
                "routeAnnotations" : {
                  "haproxy.router.openshift.io|timeout" : "30s"
                 }
             },
             "simple-mock": {
               "service": "simple-mock",
                "routeAnnotations" : {
                  "haproxy.router.openshift.io|timeout" : "30s"
                 }
             }
           }
        }
        """, createdResources = 4
        )

        val bigIpSpec1: BigIpSpec = (bigip1.resource as BigIp).spec
        assertThat(route1.resource.metadata.name).isEqualTo(bigIpSpec1.routeName)

        val bigIpSpec2: BigIpSpec = (bigip2.resource as BigIp).spec
        assertThat(route2.resource.metadata.name).isEqualTo(bigIpSpec2.routeName)

        assertThat(route1).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("MultipleRoutes/route-1.json")

        assertThat(bigip1).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("MultipleRoutes/bigip-1.json")

        assertThat(route2).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("MultipleRoutes/route-2.json")

        assertThat(bigip2).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("MultipleRoutes/bigip-2.json")
    }

    @Test
    fun `should only generate simple-2 route when simple is disabled`() {

        val (route, bigip) = generateResources(
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
             "simple-mock": {
               "service": "simple-mock",
                "routeAnnotations" : {
                  "haproxy.router.openshift.io|timeout" : "30s"
                 }
             }
           }
        }
        """, createdResources = 2
        )

        val bigIpSpec1: BigIpSpec = (bigip.resource as BigIp).spec
        assertThat(route.resource.metadata.name).isEqualTo(bigIpSpec1.routeName)

        assertThat(route).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("MultipleRoutes/route-2.json")

        assertThat(bigip).auroraResourceCreatedByThisFeature()
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
              "enabled": true,
              "service" : "simple",
              "asmPolicy": "something",
              "externalHost": "localhost",
              "oauthScopes": "test",
              "apiPaths": "/api/simple/,/web/simple/",
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
