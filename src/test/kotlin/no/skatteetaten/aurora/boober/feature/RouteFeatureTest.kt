package no.skatteetaten.aurora.boober.feature

import assertk.Assert
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import assertk.assertions.support.expected
import assertk.assertions.support.show
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.service.MultiApplicationValidationException
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.singleApplicationError
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RouteFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = RouteFeature(".test.foo")

    @Test
    fun `should get error if route with invalid tls termination`() {
        assertThat {
            createAuroraDeploymentContext(
                """{
            "route" : {
               "foo" : {
                 "tls" : {
                   "termination" : "foobar"
                 }
               }
            }
        }"""
            )
        }.singleApplicationError(
            "Config for application simple in environment utv contains errors. Must be one of [edge, passthrough]."
        )
    }

    @Test
    fun `should get error if route with invalid tls insecure policy`() {
        assertThat {
            createAuroraDeploymentContext(
                """{
            "route" : {
               "foo" : {
                 "tls" : {
                   "insecurePolicy" : "foobar"
                 }
               }
            }
        }"""
            )
        }.singleApplicationError(
            "Config for application simple in environment utv contains errors. Must be one of [Redirect, None, Allow]"
        )
    }

    @Test
    fun `should get error if route with annotation has slash in name`() {
        assertThat {
            createAuroraDeploymentContext(
                """{
            "route" : {
               "foo" : {
                 "annotations" : {
                   "foo/bar" : true
                 }
               }
            }
        }"""
            )
        }.singleApplicationError(
            "Config for application simple in environment utv contains errors. Annotation foo/bar cannot contain '/'. Use '|' instead."
        )
    }

    @Test
    fun `should get error if route with tls have dot in host`() {
        assertThat {
            createAuroraDeploymentContext(
                """{
            "route" : {
               "foo" : {
                 "host" : "foo.bar",
                 "tls" : {
                   "enabled" : true
                 }
               }
            }
        }"""
            )
        }.singleApplicationError(
            "Application simple in environment utv have a tls enabled route with a '.' in the host. Route name=simple-foo with tls uses '.' in host name."
        )
    }

    // TODO: Does these error messages have to include the first sentence. All errors are wrapped in context about this.
    @Test
    fun `should get error if two routes have duplicate targets`() {
        assertThat {
            createAuroraDeploymentContext(
                """{
            "route" : {
               "foo" : {
                 "enabled" : true
                 }, 
               "bar" : {
                 "enabled" : true
               }
            }
        }"""
            )
        }.singleApplicationError(
            "Application simple in environment utv have duplicated host+path configurations. host=simple-paas-utv is not unique. Remove the configuration from one of the following routes simple-foo,simple-bar."
        )
    }

    @Test
    fun `should get error if two routes have duplicate names`() {
        assertThat {
            createAuroraDeploymentContext(
                """{
            "route" : {
               "simple" : {
                 "host" : "foo"
               }, 
               "@name@" : {
                 "enabled" : true
               }
            }
        }"""
            )
        }.singleApplicationError(
            "Application simple in environment utv have routes with duplicate names. Route name=simple is duplicated."
        )
    }

    @Test
    fun `should ignore if route disabled`() {

        val resources = generateResources(
            """{
            "route" : false
        }"""
        )

        assertThat(resources.size).isEqualTo(0)
    }

    @Test
    fun `should generate simple route`() {

        val (dcResource, routeResource) = generateResources(
            """{
            "route" : "true"
        }""", createEmptyDeploymentConfig()
        )

        assertThat(routeResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("route.json")

        assertThat(dcResource).auroraRouteEnvAdded("simple-paas-utv.test.foo")
    }

    @Test
    fun `should generate fullyQualified route`() {

        val (dcResource, routeResource) = generateResources(
            """{
            "route" : { 
              "simple" : {
                "host" : "foo.bar.baz",
                "fullyQualifiedHost" : true
              }
            }
        }""", createEmptyDeploymentConfig()
        )

        assertThat(routeResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("fqdn-route.json")

        assertThat(dcResource).auroraRouteEnvAdded("foo.bar.baz")
    }

    @Test
    fun `should generate path based route`() {

        val (dcResource, routeResource) = generateResources(
            """{
            "route" : { 
              "simple" : {
                "path" : "/foo"
              }
            }
        }""", createEmptyDeploymentConfig()
        )

        assertThat(routeResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("path-route.json")

        assertThat(dcResource).auroraRouteEnvAdded("simple-paas-utv.test.foo/foo")
    }

    @Test
    fun `should generate route with annotations`() {

        val (dcResource, routeResource) = generateResources(
            """{
            "route" : { 
              "simple" : {
                "annotations" : {
                  "foo|bar" : "bar"
                }
              }
            },
            "routeDefaults" : {
              "annotations" : {
                "foo|baz" : "baz"
              }
             }
        }""", createEmptyDeploymentConfig()
        )

        assertThat(routeResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("annotations-route.json")

        assertThat(dcResource).auroraRouteEnvAdded("simple-paas-utv.test.foo")
    }

    @Test
    fun `should generate route with annotations overriding default value`() {

        val (dcResource, routeResource) = generateResources(
            """{
            "route" : { 
              "simple" : {
                "annotations" : {
                  "foo|baz" : "baz",
                  "foo|bar" : "bar"
                }
              }
            },
            "routeDefaults" : {
              "annotations" : {
                "foo|baz" : "bal"
              }
             }
        }""", createEmptyDeploymentConfig()
        )

        assertThat(routeResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("annotations-route.json")

        assertThat(dcResource).auroraRouteEnvAdded("simple-paas-utv.test.foo")
    }

    @Test
    fun `should generate simple route with tls`() {

        val (dcResource, routeResource) = generateResources(
            """{
            "route" : "true",
            "routeDefaults" : {
              "tls" : {
                "enabled" : true
              }
            }
        }""", createEmptyDeploymentConfig()
        )

        assertThat(routeResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("tls-route.json")

        assertThat(dcResource).auroraRouteEnvAdded("simple-paas-utv.test.foo", "https")
    }

    @Test
    fun `should ignore disabled route`() {

        val (dcResource, routeResource) = generateResources(
            """{
            "route" : {
               "foo" : {
                 "host" : "simple-foo"
                 }, 
               "bar" : {
                 "enabled" : false
               }
            }
        }""", createEmptyDeploymentConfig()
        )

        assertThat(routeResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("foo-route.json")

        assertThat(dcResource).auroraRouteEnvAdded("simple-foo.test.foo")
    }

    @Test
    fun `should generate two routes`() {

        val (dcResource, routeResource, route2Resource) = generateResources(
            """{
            "route" : {
               "foo" : {
                 "host" : "simple-foo"
                 }, 
               "bar" : {
                 "enabled" : true
               }
            }
        }""",
            resource = createEmptyDeploymentConfig(),
            createdResources = 2
        )

        assertThat(routeResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("foo-route.json")

        assertThat(route2Resource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("bar-route.json")

        assertThat(dcResource).auroraRouteEnvAdded("simple-foo.test.foo")
    }

    @Test
    fun `should generate two routes with tls on one of them`() {

        val (dcResource, routeResource, route2Resource) = generateResources(
            """{
            "route" : {
               "foo" : {
                 "host" : "simple-foo",
                 "tls" : {
                   "enabled" : true
                 }
               }, 
               "bar" : {
                 "enabled" : true
               }
            }
        }""",
            resource = createEmptyDeploymentConfig(),
            createdResources = 2
        )

        assertThat(routeResource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("foo-tls-route.json")
        assertThat(route2Resource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("bar-route.json")
        assertThat(dcResource).auroraRouteEnvAdded("simple-foo.test.foo", "https")
    }

    fun Assert<AuroraResource>.auroraRouteEnvAdded(host: String, protocol: String = "http") = transform { ar ->

        assertThat(ar).auroraResourceModifiedByThisFeatureWithComment("Added env vars")
        val dc = ar.resource as DeploymentConfig

        val actual = dc.spec.template.spec.containers.first().env.associate { it.name to it.value }
        val expected = mapOf(
            "ROUTE_NAME" to host,
            "ROUTE_URL" to "$protocol://$host"
        )
        if (expected != actual) {
            expected(":${show(actual)} to be:${show(expected)}")
        }
    }

    @Test
    fun `should create resource if route is simplified`() {
        val ctx = createAuroraDeploymentContext(
            """{
                  "route" : true
                }"""
        )

        val routeFeature: RouteFeature = feature as RouteFeature
        assertThat(routeFeature.willCreateResource(ctx.spec, ctx.cmd)).isTrue()
    }

    @Test
    fun `should create route if simplified overwritten and enabled expanded`() {
        val ctx = createAuroraDeploymentContext(
            """{
                     "route" : {
                              "foo" : {
                                "enabled" : true
                              }
                            }
                }""", files = listOf(
                AuroraConfigFile(
                    "utv/about.json", contents = """{
                        "route" : false
                         }"""
                )
            )
        )

        val routeFeature: RouteFeature = feature as RouteFeature
        assertThat(routeFeature.willCreateResource(ctx.spec, ctx.cmd)).isTrue()
    }

    @Test
    fun `should not create route if overrwritten and diabled`() {
        val ctx = createAuroraDeploymentContext(
            """{
                  "route" : false
                }""", files = listOf(
                AuroraConfigFile(
                    "utv/about.json", contents = """{
                            "route" : {
                              "foo" : {
                                "enabled" : true
                              }
                            }
                         }"""
                )
            )
        )

        val routeFeature: RouteFeature = feature as RouteFeature
        assertThat(routeFeature.willCreateResource(ctx.spec, ctx.cmd)).isFalse()
    }

    @Test
    fun `that a disabled cname does not make any difference to configuration`() {
        val (dcResource, routeResource) = generateResources(
            """{
            "route" : {
               "foo" : {
                 "host" : "simple-foo",
                 "cname": {
                   "enabled": false
                 }
               }
            }
        }""",
            resource = createEmptyDeploymentConfig(),
            createdResources = 1
        )

        assertThat(routeResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("foo-route.json")

        assertThat(dcResource).auroraRouteEnvAdded("simple-foo.test.foo")
    }

    @Test
    fun `that enabled cname generates an auroracname entry`() {
        val (dcResource, routeResource, cnameResource) = generateResources(
            """{
            "route" : {
               "foo" : {
                 "host" : "simple-foo",
                 "cname": {
                   "host": "simple-foo-specific-cname",
                   "enabled": true                    
                 }
               }
            }
        }""",
            resource = createEmptyDeploymentConfig(),
            createdResources = 2
        )
        // There shall be 2 resource created by this

        assertThat(routeResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("foo-route.json")
        assertThat(cnameResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("foo-aurora-cname.json")

        assertThat(dcResource).auroraRouteEnvAdded("simple-foo.test.foo")
    }

    @Test
    fun `specified cname has deterministic content`() {
        val (dcResource, routeResource, cnameResource) = generateResources(
            """{
            "route" : {
               "foo" : {
                 "host" : "simple-foo",
                 "cname": {
                   "enabled": "true",
                   "host": "not-just-default.utv.apps.paas.skead.no",
                   "ttl": 150
                 }
               }
            }
        }""",
            resource = createEmptyDeploymentConfig(),
            createdResources = 2
        )

        assertThat(routeResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("foo-route.json")
        assertThat(cnameResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("simple-foo-aurora-cname.json")

        assertThat(dcResource).auroraRouteEnvAdded("simple-foo.test.foo")
    }

    @Test
    fun `that disabled route and enabled cname will not generate resources`() {
        generateResources(
            """{
            "route" : {
               "foo" : {
                 "host" : "simple-foo",
                 "enabled": "false",
                 "cname": {
                   "enabled": "true",
                   "host": "not-just-default.utv.apps.paas.skead.no",
                   "ttl": 300
                 }
               }
            }
        }""",
            resource = createEmptyDeploymentConfig(),
            createdResources = 0
        )
    }

    @Test
    fun `should generate cname after overriding default value`() {
        val (dcResource, routeResource, cnameResource) = generateResources(
            """{
            "route" : { 
               "foo" : {
                 "host" : "simple-foo",
                 "cname" : {
                    "host": "simple-foo-specific-cname"
                 }
               }
            },
            "routeDefaults" : {
              "cname" : {
                "enabled" : "true",
                "host": "this-cname-alias-target-gets-overwritten"
              }
             }
        }""",
            resource = createEmptyDeploymentConfig(),
            createdResources = 2
        )

        assertThat(routeResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("foo-route.json")
        assertThat(cnameResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("foo-aurora-cname.json")

        assertThat(dcResource).auroraRouteEnvAdded("simple-foo.test.foo")
    }

    @Test
    fun `default cname host value is used as cname target`() {
        val (dcResource, routeResource, cnameResource) = generateResources(
            """{
            "route" : { 
                "foo" : {
                    "enabled" : "true"
                }
            },
            "routeDefaults" : {
              "host" : "simple-foo",
              "cname" : {
                "enabled" : "true",
                "host": "simple-foo-specific-cname"
              }
            }
            }""",
            resource = createEmptyDeploymentConfig(),
            createdResources = 2
        )

        assertThat(routeResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("foo-route.json")
        assertThat(cnameResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("foo-aurora-cname.json")

        assertThat(dcResource).auroraRouteEnvAdded("simple-foo.test.foo")
    }

    @Test
    fun `that cname host value is used as cname target`() {
        val (dcResource, routeResource, cnameResource) = generateResources(
            """{
            "route" : "true",
            "routeDefaults" : {
              "cname" : {
                "enabled" : "true",
                "host": "@name@-specific-cname"
              }
            }
            }""",
            resource = createEmptyDeploymentConfig(),
            createdResources = 2
        )

        assertThat(routeResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("route.json")

        assertThat(cnameResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("aurora-cname.json")

        assertThat(dcResource).auroraRouteEnvAdded("simple-paas-utv.test.foo")
    }

    @Test
    fun `cname reference cannot just be set for simple route`() {
        val ex: MultiApplicationValidationException = assertThrows {
            val (dcResource, routeResource) = generateResources(
                """{
            "route" : "true",
            "simple" : {
              "cname" : {
                 "enabled": "true"
              }
            }
        }""", createEmptyDeploymentConfig()
            )
        }
        assertThat { ex.message?.contains("not a valid config field pointer") }
    }
}
