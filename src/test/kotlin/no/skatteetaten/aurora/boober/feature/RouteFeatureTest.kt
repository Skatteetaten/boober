package no.skatteetaten.aurora.boober.feature

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
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
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.singleApplicationError
import no.skatteetaten.aurora.boober.utils.singleApplicationErrorResult

class RouteFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = RouteFeature(".test.foo")

    // Number of "o" characters is 61
    private val sixtyOne = "ooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooo"
    private val sixtyThree = "${sixtyOne}oo"
    private val twoHundred53 = "$sixtyThree.$sixtyThree.$sixtyThree.$sixtyOne"

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
        }.singleApplicationErrorResult(
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
        }.singleApplicationErrorResult(
            "Config for application simple in environment utv contains errors. Must be one of [Redirect, None, Allow]"
        )
    }

    @Test
    fun `separate routes for onprem and azure with cname`() {
        val (dcResource, routeResource, azureRouteResource, auroraCname) = generateResources(
            """{
            "route" : {
               "simple" : {
                    "host": "simple-specific-cname.foo.no",
                    "azure": {
                       "enabled": true,
                       "cnameTtl": 100
                    },
                    "cname": {
                       "enabled": true,
                       "ttl": 150
                    }
               }
            }
        }""",
            createEmptyDeploymentConfig(),
            createdResources = 3
        )

        assertThat(routeResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("route-with-specific-cname.json")

        assertThat(azureRouteResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("route-with-azure-specific-host.json")

        assertThat(auroraCname).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("aurora-cname-azure-specific-host.json")

        assertThat(dcResource).auroraRouteEnvAdded("simple-specific-cname.foo.no")
    }

    @Test
    fun `should be able to disable azure route`() {
        val (dcResource, routeResource) = generateResources(
            """{
            "routeDefaults" : { 
                "azure": {
                    "enabled": true
                }
            },
            "route" : {
               "simple" : {
                    "azure": {
                       "enabled": false
                    }
               }
            }
        }""",
            createEmptyDeploymentConfig(),
            createdResources = 1
        )

        assertThat(routeResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("route.json")

        assertThat(dcResource).auroraRouteEnvAdded("simple-paas-utv.test.foo")
    }

    @Test
    fun `should have separate routes for onprem and azure with azure defaults`() {
        val (dcResource, routeResource, azureRouteResource, auroraCname) = generateResources(
            """{
            "routeDefaults" : { 
                "azure": {
                    "enabled": true
                }
            },
            "route" : true
        }""",
            createEmptyDeploymentConfig(),
            createdResources = 3
        )

        assertThat(routeResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("route.json")

        assertThat(azureRouteResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("route-with-azure.json")

        assertThat(auroraCname).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("aurora-cname-azure.json")

        assertThat(dcResource).auroraRouteEnvAdded("simple-paas-utv.test.foo")
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
        }.singleApplicationErrorResult(
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
        }.singleApplicationErrorResult(
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
        }.singleApplicationErrorResult(
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
        }.singleApplicationErrorResult(
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
        }""",
            createEmptyDeploymentConfig()
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
        }""",
            createEmptyDeploymentConfig()
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
        }""",
            createEmptyDeploymentConfig()
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
        }""",
            createEmptyDeploymentConfig()
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
        }""",
            createEmptyDeploymentConfig()
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
        }""",
            createEmptyDeploymentConfig()
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
        }""",
            createEmptyDeploymentConfig()
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
            "routeDefaults": {
              "tls": {
                "enabled": true
              }
            },
            "route" : {
               "foo" : {
                 "host" : "simple-foo"
               }, 
               "bar" : {
                 "enabled" : true,
                 "tls" : {
                   "enabled" : false
                 }
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

    @Test
    fun `should create resource if route is simplified`() {
        val (valid, _) = createAuroraDeploymentContext(
            """{
                  "route" : true
                }"""
        )

        val routeFeature: RouteFeature = feature as RouteFeature
        assertThat(routeFeature.willCreateResource(valid.first().spec, valid.first().cmd)).isTrue()
    }

    @Test
    fun `should create route if simplified overwritten and enabled expanded`() {
        val (valid, _) = createAuroraDeploymentContext(
            """{
                     "route" : {
                              "foo" : {
                                "enabled" : true
                              }
                            }
                }""",
            files = listOf(
                AuroraConfigFile(
                    "utv/about.json",
                    contents = """{
                        "route" : false
                         }"""
                )
            )
        )

        val routeFeature: RouteFeature = feature as RouteFeature
        assertThat(routeFeature.willCreateResource(valid.first().spec, valid.first().cmd)).isTrue()
    }

    @Test
    fun `should not create route if overrwritten and diabled`() {
        val (valid, _) = createAuroraDeploymentContext(
            """{
                  "route" : false
                }""",
            files = listOf(
                AuroraConfigFile(
                    "utv/about.json",
                    contents = """{
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
        assertThat(routeFeature.willCreateResource(valid.first().spec, valid.first().cmd)).isFalse()
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
                 "host" : "simple-foo-specific-cname",
                 "cname": {
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
            .auroraResourceMatchesFile("foo-route-with-cname.json")
        assertThat(cnameResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("foo-aurora-cname.json")

        assertThat(dcResource).auroraRouteEnvAdded("simple-foo-specific-cname")
    }

    @Test
    fun `specified cname ttl is overridden correctly`() {
        val (dcResource, routeResource, cnameResource) = generateResources(
            """{
            "route" : {
               "foo" : {
                 "host": "not-just-default.utv.apps.paas.skead.no",
                 "cname": {
                   "enabled": "true",
                   "ttl": 150
                 }
               }
            }
        }""",
            resource = createEmptyDeploymentConfig(),
            createdResources = 2
        )

        assertThat(routeResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("foo-route-with-not-default-cname.json")
        assertThat(cnameResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("not-just-default-aurora-cname.json")

        assertThat(dcResource).auroraRouteEnvAdded("not-just-default.utv.apps.paas.skead.no")
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
                 "host" : "simple-foo-specific-cname"
               }
            },
            "routeDefaults" : {
              "host": "this-cname-alias-target-gets-overwritten",
              "cname" : {
                "enabled" : "true"
              }
             }
        }""",
            resource = createEmptyDeploymentConfig(),
            createdResources = 2
        )

        assertThat(routeResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("foo-route-with-cname.json")
        assertThat(cnameResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("foo-aurora-cname.json")

        assertThat(dcResource).auroraRouteEnvAdded("simple-foo-specific-cname")
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
              "host": "simple-foo-specific-cname",
              "cname" : {
                "enabled" : "true"
              }
            }
            }""",
            resource = createEmptyDeploymentConfig(),
            createdResources = 2
        )

        assertThat(routeResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("foo-route-with-cname.json")
        assertThat(cnameResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("foo-aurora-cname.json")

        assertThat(dcResource).auroraRouteEnvAdded("simple-foo-specific-cname")
    }

    @Test
    fun `that enabled route gets cname if set as default`() {
        val (dcResource, routeResource, cnameResource) = generateResources(
            """{
            "route" : "true",
            "routeDefaults" : {
              "host": "simple-specific-cname",
              "cname" : {
                "enabled" : "true"
              }
            }
            }""",
            resource = createEmptyDeploymentConfig(),
            createdResources = 2
        )

        assertThat(routeResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("route-with-cname.json")

        assertThat(cnameResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("aurora-cname.json")

        assertThat(dcResource).auroraRouteEnvAdded("simple-specific-cname")
    }

    @Test
    fun `cname gets generated just enabling it`() {
        val (dcResource, routeResource, cnameResource) = generateResources(
            """{
            "route" : "true",
            "routeDefaults" : {
              "cname" : {
                 "enabled": "true"
              }
            }
        }""",
            createEmptyDeploymentConfig(),
            createdResources = 2
        )
        assertThat(routeResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("route-with-cname-simple.json")

        assertThat(cnameResource).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("aurora-cname-simple.json")

        assertThat(dcResource).auroraRouteEnvAdded("simple-paas-utv")
    }

    @Test
    fun `a dns host name can be 253 chars`() {
        val (dcResource) = generateResources(
            """
                {
                  "route" : {
                    "foo" : {
                      "host": "$twoHundred53",
                      "fullyQualifiedHost" : true
                    }
                  }
                }
            """.trimIndent(),
            createEmptyDeploymentConfig(),
            createdResources = 1
        )
        assertThat(dcResource).auroraRouteEnvAdded("$twoHundred53")
    }

    @Test
    fun `a dns host name cannot be more than 253 chars`() {
        assertThat {
            generateResources(
                """
                {
                  "route" : {
                    "foo" : {
                      "host": "X$twoHundred53",
                      "fullyQualifiedHost" : true
                    }
                  }
                }
                """.trimIndent(),
                createEmptyDeploymentConfig(),
                createdResources = 1
            )
        }.singleApplicationError("Application simple in environment utv has invalid dns name")
    }

    @Test
    fun `a node in a host is allowed to be 63 chars`() {
        val (dcResource) = generateResources(
            """
                {
                  "route" : {
                    "foo" : {
                      "host": "$sixtyThree"
                    }
                  }
                }
            """.trimIndent(),
            createEmptyDeploymentConfig(),
            createdResources = 1
        )
        assertThat(dcResource).auroraRouteEnvAdded("$sixtyThree.test.foo")
    }

    @Test
    fun `a host name cannot exceed 63 chars`() {
        assertThat {
            generateResources(
                """
                {
                  "route" : {
                    "foo" : {
                      "host": "X$sixtyThree"
                    }
                  }
                }
                """.trimIndent(),
                createEmptyDeploymentConfig()
            )
        }.singleApplicationError("Application simple in environment utv has invalid dns name")
    }

    @Test
    fun `when having cname, dns must be valid`() {
        assertThat {
            generateResources(
                """
                {
                  "route" : {
                    "foo" : {
                      "enabled" : "true",
                      "host": "$sixtyThree.X$sixtyThree",
                      "cname" : {
                        "enabled" : "true"
                      }
                    }
                  }
                }
                """.trimIndent(),
                resource = createEmptyDeploymentConfig(), createdResources = 3
            )
        }.singleApplicationError("Application simple in environment utv has invalid dns name")
    }

    @Test
    fun `a node in a fqdn host statement cannot exceed 63 chars`() {
        assertThat {
            generateResources(
                """
                {
                  "route" : {
                    "simple" : {
                      "host": "$sixtyThree.X$sixtyThree",
                      "fullyQualifiedHost" : true
                    }
                  }
                }
                """.trimIndent(),
                createEmptyDeploymentConfig()
            )
        }.singleApplicationError("Application simple in environment utv has invalid dns name")
    }

    @Test
    fun `default host nodes cannot exceed 63 chars`() {
        assertThat {
            generateResources(
                """
                {
                  "route" : {
                    "foo" : {
                      "enabled" : "true"
                    }
                  },
                  "routeDefaults" : {
                    "host": "$sixtyThree.X$sixtyThree",
                    "cname" : {
                      "enabled" : "true"
                    }
                  }
                }
                """.trimIndent(),
                createEmptyDeploymentConfig()
            )
        }.singleApplicationError("Application simple in environment utv has invalid dns name")
    }

    @ParameterizedTest
    @CsvSource(value = ["x.xx", "x1.xx", "x-1.xx", "some-x.org"])
    fun `valid host names should not fail`(dns: String) {
        val (dcResource) = generateResources(
            """{
                  "route": {
                    "foo": {
                      "host": "$dns",
                      "cname": {
                        "enabled": "true"
                      }
                    }
                  }
                }
            """.trimIndent(),
            createEmptyDeploymentConfig(),
            createdResources = 2
        )
        assertThat(dcResource).auroraRouteEnvAdded(dns)
    }

    @ParameterizedTest
    @CsvSource(value = ["x_1.xx", "some.org-", "-some.org", ".no", "x.no.", "x..no"])
    fun `invalid host names should fail`(dns: String) {
        assertThat {
            generateResources(
                """{
                    "route": {
                        "foo" : {
                            "host": "$dns",
                            "cname" : { 
                                "enabled" : "true" 
                            }
                        }
                    }
                }
                """.trimIndent(),
                createEmptyDeploymentConfig(),
                createdResources = 2
            )
        }.singleApplicationError("Application simple in environment utv has invalid dns name")
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
            this.expected(":${show(actual)} to be:${show(expected)}")
        }
    }
}
