package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import org.junit.jupiter.api.Test

class RouteFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = RouteFeature(".test.foo")


    @Test
    fun `should generate simple route`() {

        val resources = generateResources("""{
            "route" : "true"
        }""", createEmptyDeploymentConfig())

        assertThat(resources.size).isEqualTo(2)

        val (dcResource, routeResource) = resources.toList()

        assertThat(routeResource).auroraResourceCreatedByThisFeature()
                .auroraResourceMatchesFile("route.json")

        assertThat(dcResource).auroraResourceModifiedByThisFeatureWithComment("Added env vars")

        val dc = dcResource.resource as DeploymentConfig
        assertThat(dc.spec.template.spec.containers.first().env.associate { it.name to it.value }).isEqualTo(
                mapOf("ROUTE_NAME" to "simple-paas-utv.test.foo",
                        "ROUTE_URL" to "http://simple-paas-utv.test.foo")
        )
    }

    @Test
    fun `should get error if two routes have duplicate targets`() {
        assertThat {
            createAuroraDeploymentContext("""{
            "route" : {
               "foo" : {
                 "enabled" : true
                 }, 
               "bar" : {
                 "enabled" : true
               }
            }
        }""")
        }.singleApplicationError(
                "Application simple in environment utv have duplicated targets. target=simple-paas-utv is duplicated in routes simple-foo,simple-bar."
        )
    }

    @Test
    fun `should generate two routes`() {

        val resources = generateResources("""{
            "route" : {
               "foo" : {
                 "host" : "simple-foo"
                 }, 
               "bar" : {
                 "enabled" : true
               }
            }
        }""", createEmptyDeploymentConfig())

        assertThat(resources.size).isEqualTo(3)

        val (dcResource, routeResource, route2Resource) = resources.toList()

        assertThat(routeResource).auroraResourceCreatedByThisFeature()
                .auroraResourceMatchesFile("foo-route.json")

        assertThat(route2Resource).auroraResourceCreatedByThisFeature()
                .auroraResourceMatchesFile("bar-route.json")

        assertThat(dcResource).auroraResourceModifiedByThisFeatureWithComment("Added env vars")

        val dc = dcResource.resource as DeploymentConfig
        assertThat(dc.spec.template.spec.containers.first().env.associate { it.name to it.value }).isEqualTo(
                mapOf("ROUTE_NAME" to "simple-foo.test.foo",
                        "ROUTE_URL" to "http://simple-foo.test.foo")
        )
    }


}