package no.skatteetaten.aurora.boober.feature

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.api.model.Service
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.facade.json
import no.skatteetaten.aurora.boober.feature.toxiproxy.ToxiproxySidecarFeature
import no.skatteetaten.aurora.boober.model.PortNumbers
import no.skatteetaten.aurora.boober.service.CantusService
import no.skatteetaten.aurora.boober.service.ImageMetadata
import no.skatteetaten.aurora.boober.service.MultiApplicationValidationException
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseSchemaInstance
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseSchemaProvisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DbApiEnvelope
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DbhRestTemplateWrapper
import no.skatteetaten.aurora.boober.utils.AbstractMultiFeatureTest
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.HttpMock
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.httpMockServer
import org.apache.commons.codec.binary.Base64
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.web.client.RestTemplateBuilder
import java.nio.charset.Charset
import java.util.UUID

class ToxiproxySidecarFeatureTest : AbstractMultiFeatureTest() {

    val provisioner = DatabaseSchemaProvisioner(
        DbhRestTemplateWrapper(RestTemplateBuilder().build(), "http://localhost:5000", 0),
        jacksonObjectMapper()
    )

    override val features: List<Feature>
        get() = listOf(
            ConfigFeature(),
            DatabaseFeature(provisioner, userDetailsProvider, "utv"),
            ToxiproxySidecarFeature(cantusService, provisioner, userDetailsProvider, "2.1.3")
        )

    private val cantusService: CantusService = mockk()

    private val userDetailsProvider: UserDetailsProvider = mockk()

    private fun dbMock() = httpMockServer(5000) {
        rule {
            json(
                DbApiEnvelope(
                    "ok",
                    listOf(
                        createDbhSchema(
                            UUID.fromString("36eeeab0-d510-4115-9696-f50a503ee060"),
                            "jdbc:oracle:thin:@host:1234/dbname",
                            DatabaseSchemaInstance(1234, "host")
                        )
                    )
                )
            )
        }
    }

    @BeforeEach
    fun setupMock() {
        every {
            cantusService.getImageMetadata(
                "shopify", "toxiproxy", "2.1.3"
            )
        } returns
            ImageMetadata(
                "docker.registry/shopify/toxiproxy",
                "2.1.3",
                "sha:1234"
            )

        every { userDetailsProvider.getAuthenticatedUser() } returns User("username", "token")
    }

    @AfterEach
    fun clearMocks() {
        HttpMock.clearAllHttpMocks()
    }

    @Test
    fun `should add toxiproxy to dc and change service port`() {

        val (serviceResource, dcResource, configResource) = generateResources(
            """{
                "toxiproxy" : true 
            }""",
            createEmptyService(),
            createEmptyDeploymentConfig()
        )

        assertThat(serviceResource)
            .auroraResourceModifiedByFeatureWithComment(
                feature = ToxiproxySidecarFeature::class.java,
                comment = "Changed targetPort to point to toxiproxy",
            )

        val service = serviceResource.resource as Service
        assertThat(service.spec.ports.first().targetPort).isEqualTo(IntOrString(PortNumbers.TOXIPROXY_HTTP_PORT))

        assertThat(dcResource)
            .auroraResourceModifiedByFeatureWithComment(
                feature = ToxiproxySidecarFeature::class.java,
                comment = "Added toxiproxy volume and sidecar container",
            )
            .auroraResourceMatchesFile("dc.json")

        assertThat(configResource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("config.json")
    }

    @Test
    fun `Should add toxiproxy to dc and map endpoints from container`() {

        val (serviceResource, dcResource, configResource) = generateResources(
            """{
                "toxiproxy": {
                    "version": "2.1.3",
                    "proxies": {
                        "test1": {
                            "enabled": true,
                            "urlVariable": "TEST"
                        },
                        "test3": {
                            "enabled": false,
                            "urlVariable": "DISABLED_TEST"
                        },
                        "test5": {
                            "enabled": true,
                            "urlVariable": "HTTPS_URL"
                        },
                        "test6": {
                            "enabled": true,
                            "urlVariable": "URL_WITH_PORT"
                        },
                        "test7": {
                            "enabled": true,
                            "urlVariable": "URL_WITH_PATH"
                        },
                        "test8": {
                            "enabled": true,
                            "initialEnabledState": true,
                            "urlVariable": "INITIALLY_ENABLED"
                        },
                        "test9": {
                            "enabled": true,
                            "initialEnabledState": false,
                            "urlVariable": "INITIALLY_DISABLED"
                        }
                    }
                },
                "config": {
                    "TEST": "http://test1.test",
                    "DISABLED_TEST": "http://test3.test",
                    "HTTPS_URL": "https://test5.test",
                    "URL_WITH_PORT": "http://test6.test:1234",
                    "URL_WITH_PATH": "http://test7.test/path",
                    "INITIALLY_ENABLED": "http://test8.test",
                    "INITIALLY_DISABLED": "http://test9.test"
                }
            }""",
            createEmptyService(),
            createEmptyDeploymentConfig()
        )

        assertThat(serviceResource)
            .auroraResourceModifiedByFeatureWithComment(
                feature = ToxiproxySidecarFeature::class.java,
                comment = "Changed targetPort to point to toxiproxy",
            )

        val service = serviceResource.resource as Service
        assertThat(service.spec.ports.first().targetPort).isEqualTo(IntOrString(PortNumbers.TOXIPROXY_HTTP_PORT))

        assertThat(dcResource)
            .auroraResourceModifiedByFeatureWithComment(
                feature = ToxiproxySidecarFeature::class.java,
                comment = "Added toxiproxy volume and sidecar container"
            )
            .auroraResourceMatchesFile("dcWithEndpointMapping.json")

        assertThat(configResource)
            .auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("configWithEndpointMapping.json")
    }

    @Test
    fun `Should add toxiproxy to dc and map servers and ports from container`() {

        val (serviceResource, dcResource, configResource) = generateResources(
            """{
                "toxiproxy": {
                    "version": "2.1.3",
                    "proxies": {
                        "proxyName1": {
                            "serverVariable": "SERVER_1",
                            "portVariable": "PORT_1"
                        },
                        "proxyName2": {
                            "serverVariable": "SERVER_2",
                            "portVariable": "PORT_2",
                            "initialEnabledState": true
                        },
                        "proxyName3": {
                            "serverVariable": "SERVER_3",
                            "portVariable": "PORT_3",
                            "initialEnabledState": false
                        }
                    }
                },
                "config": {
                    "SERVER_1": "test1.test",
                    "PORT_1": 123,
                    "SERVER_2": "test2.test",
                    "PORT_2": 124,
                    "SERVER_3": "test3.test",
                    "PORT_3": 125
                }
            }""",
            createEmptyService(),
            createEmptyDeploymentConfig()
        )

        assertThat(serviceResource)
            .auroraResourceModifiedByFeatureWithComment(
                feature = ToxiproxySidecarFeature::class.java,
                comment = "Changed targetPort to point to toxiproxy",
            )

        val service = serviceResource.resource as Service
        assertThat(service.spec.ports.first().targetPort).isEqualTo(IntOrString(PortNumbers.TOXIPROXY_HTTP_PORT))

        assertThat(dcResource)
            .auroraResourceModifiedByFeatureWithComment(
                feature = ToxiproxySidecarFeature::class.java,
                comment = "Added toxiproxy volume and sidecar container",
            )
            .auroraResourceMatchesFile("dcWithServerAndPortMapping.json")

        assertThat(configResource)
            .auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("configWithServerAndPortMapping.json")
    }

    @Test
    fun `Should fail with an error message when an endpoint with no corresponding environment variable is given`() {

        val errorMessage = assertThrows<MultiApplicationValidationException> {
            generateResources(
                """{
                    "toxiproxy": {
                        "version": "2.1.3",
                        "proxies": {
                            "test": {"urlVariable": "NOT_EXISTING_VAR", "enabled": true}
                        }
                    }
                }""",
                createEmptyService(),
                createEmptyDeploymentConfig()
            )
        }.errors.first().errors.first().message

        val expectedErrorMessage =
            "Found Toxiproxy config for endpoint named NOT_EXISTING_VAR, but there is no such environment variable."

        assertThat(errorMessage).isEqualTo(expectedErrorMessage)
    }

    @Test
    fun `Should fail with an error message when the corresponding environment variable does not contain a valid URL`() {

        val errorMessage = assertThrows<MultiApplicationValidationException> {
            generateResources(
                """{
                    "toxiproxy": {
                        "version": "2.1.3",
                        "proxies": {
                            "test": {"urlVariable": "SOME_VAR", "enabled": true}
                        }
                    },
                    "config": {"SOME_VAR": "invalid_url"}
                }""",
                createEmptyService(),
                createEmptyDeploymentConfig()
            )
        }.errors.first().errors.first().message

        val expectedErrorMessage =
            "The format of the URL \"invalid_url\" given by the config variable SOME_VAR is not supported."

        assertThat(errorMessage).isEqualTo(expectedErrorMessage)
    }

    @Test
    fun `Should fail with an error message when serverVarible points to an inexistent variable`() {

        val errorMessage = assertThrows<MultiApplicationValidationException> {
            generateResources(
                """{
                    "toxiproxy": {
                        "version": "2.1.3",
                        "proxies": {
                            "proxyName": {
                                "serverVariable": "INEXISTENT_SERVER_VAR",
                                "portVariable": "EXISTENT_PORT_VAR"
                            }
                        }
                    },
                    "config": {"EXISTENT_PORT_VAR": 123}
                }""",
                createEmptyService(),
                createEmptyDeploymentConfig()
            )
        }.errors.first().errors.first().message

        val expectedErrorMessage =
            "Found Toxiproxy config for a server variable named INEXISTENT_SERVER_VAR, but there is no such environment variable."

        assertThat(errorMessage).isEqualTo(expectedErrorMessage)
    }

    @Test
    fun `Should fail with an error message when portVarible points to an inexistent variable`() {

        val validation = assertThrows<MultiApplicationValidationException> {
            generateResources(
                """{
                    "toxiproxy": {
                        "version": "2.1.3",
                        "proxies": {
                            "proxyName": {
                                "serverVariable": "EXISTENT_SERVER_VAR",
                                "portVariable": "INEXISTENT_PORT_VAR"
                            }
                        }
                    },
                    "config": {"EXISTENT_SERVER_VAR": "test.test"}
                }""",
                createEmptyService(),
                createEmptyDeploymentConfig()
            )
        }
        val errorMessage = validation.errors.first().errors.first().message

        val expectedErrorMessage =
            "Found Toxiproxy config for a port variable named INEXISTENT_PORT_VAR, but there is no such environment variable."

        assertThat(errorMessage).isEqualTo(expectedErrorMessage)
    }

    @Test
    fun `Should map database secret to Toxiproxy`() {

        dbMock()

        val (serviceResource, dcResource, secretResource, configResource) = generateResources(
            """{
                "toxiproxy": {
                    "proxies": {
                        "dbProxy": {"database": true}
                    }
                },
                "database": true,
                "databaseDefaults": {"name": "simple"}
            }""",
            mutableSetOf(createEmptyService(), createEmptyDeploymentConfig()),
            createdResources = 2
        )

        assertThat(serviceResource)
            .auroraResourceModifiedByFeatureWithComment(
                feature = ToxiproxySidecarFeature::class.java,
                comment = "Changed targetPort to point to toxiproxy",
            )

        assertThat(dcResource)
            .auroraResourceModifiedByFeatureWithComment(
                feature = ToxiproxySidecarFeature::class.java,
                comment = "Added toxiproxy volume and sidecar container",
            )
            .auroraResourceMatchesFile("dcWithDatabaseMapping.json")

        assertThat(secretResource)
            .auroraResourceModifiedByFeatureWithComment(
                feature = ToxiproxySidecarFeature::class.java,
                comment = "Changed JDBC URL to point to Toxiproxy",
            )

        val jdbcUrl = Base64
            .decodeBase64((secretResource.resource as Secret).data["jdbcurl"])
            .toString(Charset.defaultCharset())

        assertThat(jdbcUrl).isEqualTo("jdbc:oracle:thin:@localhost:18000/dbname")

        assertThat(configResource)
            .auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("configWithDatabaseMapping.json")
    }

    @Test
    fun `Should map database secrets to Toxiproxy proxies`() {

        httpMockServer(5000) {
            rule {
                when {
                    path.contains("name%3Ddb1") ->
                        json(
                            DbApiEnvelope(
                                "ok",
                                listOf(
                                    createDbhSchema(
                                        UUID.fromString("db651af4-5fec-4875-bdef-0651b9b72691"),
                                        "jdbc:oracle:thin:@host:1234/db1",
                                        DatabaseSchemaInstance(1234, "host")
                                    )
                                )
                            )
                        )
                    path.contains("name%3Ddb2") ->
                        json(
                            DbApiEnvelope(
                                "ok",
                                listOf(
                                    createDbhSchema(
                                        UUID.fromString("5b5c2057-c8de-45e8-b41a-e897cf3800c9"),
                                        "jdbc:oracle:thin:@host:1234/db2",
                                        DatabaseSchemaInstance(1234, "host")
                                    )
                                )
                            )
                        )
                    else -> null
                }
            }
        }

        val (serviceResource, dcResource, secretResource1, secretResource2, configResource) = generateResources(
            """{
                "toxiproxy": {
                    "proxies": {
                        "proxy1": {
                            "enabled": true,
                            "databaseName": "db1"
                        },
                        "proxy2": {
                            "enabled": true,
                            "databaseName": "db2"
                        }
                    }
                },
                "database": {
                    "db1": {"enabled": true},
                    "db2": {"enabled": true}
                }
            }""",
            mutableSetOf(createEmptyService(), createEmptyDeploymentConfig()),
            createdResources = 3
        )

        assertThat(serviceResource)
            .auroraResourceModifiedByFeatureWithComment(
                feature = ToxiproxySidecarFeature::class.java,
                comment = "Changed targetPort to point to toxiproxy",
            )

        assertThat(dcResource)
            .auroraResourceModifiedByFeatureWithComment(
                feature = ToxiproxySidecarFeature::class.java,
                comment = "Added toxiproxy volume and sidecar container",
            )

        assertThat(secretResource1)
            .auroraResourceModifiedByFeatureWithComment(
                feature = ToxiproxySidecarFeature::class.java,
                comment = "Changed JDBC URL to point to Toxiproxy",
            )

        assertThat(secretResource2)
            .auroraResourceModifiedByFeatureWithComment(
                feature = ToxiproxySidecarFeature::class.java,
                comment = "Changed JDBC URL to point to Toxiproxy",
            )

        val jdbcUrl1 = Base64
            .decodeBase64((secretResource1.resource as Secret).data["jdbcurl"])
            .toString(Charset.defaultCharset())

        val jdbcUrl2 = Base64
            .decodeBase64((secretResource2.resource as Secret).data["jdbcurl"])
            .toString(Charset.defaultCharset())

        assertThat(jdbcUrl1).isEqualTo("jdbc:oracle:thin:@localhost:18000/db1")
        assertThat(jdbcUrl2).isEqualTo("jdbc:oracle:thin:@localhost:18001/db2")

        assertThat(configResource)
            .auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("configWithNamedDatabaseProxiesMapping.json")
    }

    @Test
    fun `Should fail with an error message when there are missing databases`() {

        val errorMessage = assertThrows<MultiApplicationValidationException> {
            generateResources(
                """{
                    "toxiproxy": {
                        "version": "2.1.3",
                        "proxies": {
                            "proxy1": {"databaseName": "db1"}
                        }
                    }
                }""",
                createEmptyService(),
                createEmptyDeploymentConfig()
            )
        }.errors.first().errors.first().message

        val expectedErrorMessage =
            "Found Toxiproxy config for database named db1, but there is no such database configured."

        assertThat(errorMessage).isEqualTo(expectedErrorMessage)
    }

    @Test
    fun `Should not add Toxiproxy for default database when the enabled field for the database is false`() {

        dbMock()

        val (_, dcResource, _, configResource) = generateResources(
            """{
                "toxiproxy": {
                    "proxies": {
                        "dbProxy": {
                            "database": true,
                            "enabled": false
                        }
                    }
                },
                "database": true
            }""",
            mutableSetOf(createEmptyService(), createEmptyDeploymentConfig()),
            createdResources = 2
        )
        assertThat(dcResource).auroraResourceMatchesFile("dcWithDatabaseMapping.json")
        assertThat(configResource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("config.json")
    }

    @Test
    fun `Should not add Toxiproxy for named database when the enabled field for the database is false`() {

        dbMock()

        val (_, dcResource, _, configResource) = generateResources(
            """{
                "toxiproxy": {
                    "proxies": {
                        "customproxyname": {
                            "enabled": false,
                            "databaseName": "customdbname"
                        }
                    }
                },
                "database": {"customdbname": true}
            }""",
            mutableSetOf(createEmptyService(), createEmptyDeploymentConfig()),
            createdResources = 2
        )
        assertThat(dcResource).auroraResourceMatchesFile("dcWithCustomDatabaseMapping.json")
        assertThat(configResource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("config.json")
    }

    @Test
    fun `Should fail with an error message when there is not enough information for a proxy`() {

        val errorMessage = assertThrows<MultiApplicationValidationException> {
            generateResources(
                """{
                    "toxiproxy": {
                        "version": "2.1.3",
                        "proxies": {
                            "badProxy": {
                                "enabled": true,
                                "initialEnabledState": true
                            }
                        }
                    }
                }""",
                createEmptyService(),
                createEmptyDeploymentConfig()
            )
        }.errors.first().errors.first().message

        val expectedErrorMessage = "Neither of the fields urlVariable, serverVariable, portVariable, database or " +
            "databaseName are set for the Toxiproxy proxy named badProxy. A valid configuration must contain a value " +
            "for exactly one of the properties urlVariable, database, or databaseName, or both the properties " +
            "serverVariable and portVariable."

        assertThat(errorMessage).isEqualTo(expectedErrorMessage)
    }

    @Test
    fun `Should fail with error messages when there are proxies with invalid property combinations`() {

        dbMock()

        val errorMessagesNamedDatabase = assertThrows<MultiApplicationValidationException> {
            generateResources(
                """{
                    "toxiproxy": {
                        "version": "2.1.3",
                        "proxies": {
                            "badProxy1": {
                                "urlVariable": "URL_VARIABLE",
                                "serverVariable": "SERVER_VARIABLE",
                                "portVariable": "PORT_VARIABLE"
                            },
                            "badProxy2": {
                                "urlVariable": "URL_VARIABLE",
                                "databaseName": "dbName"
                            }
                        }
                    },
                    "config": {
                        "URL_VARIABLE": "http://test1.test",
                        "SERVER_VARIABLE": "test2.test",
                        "PORT_VARIABLE": 123
                    },
                    "database": {
                        "dbName": true
                    }
                }""",
                createEmptyService(),
                createEmptyDeploymentConfig()
            )
        }.errors.first().errors.map { it.message }

        val expectedErrorMessage1 = "The combination of fields specified for the Toxiproxy proxy named badProxy1 is " +
            "not valid. A valid configuration must contain a value for exactly one of the properties urlVariable, " +
            "database, or databaseName, or both the properties serverVariable and portVariable."

        val expectedErrorMessage2 = "The combination of fields specified for the Toxiproxy proxy named badProxy2 is " +
            "not valid. A valid configuration must contain a value for exactly one of the properties urlVariable, " +
            "database, or databaseName, or both the properties serverVariable and portVariable."

        assertThat(errorMessagesNamedDatabase).hasSize(2)
        assertThat(errorMessagesNamedDatabase[0]).isEqualTo(expectedErrorMessage1)
        assertThat(errorMessagesNamedDatabase[1]).isEqualTo(expectedErrorMessage2)

        val errorMessageDefaultDatabase = assertThrows<MultiApplicationValidationException> {
            generateResources(
                """{
                    "toxiproxy": {
                        "version": "2.1.3",
                        "proxies": {
                            "badProxy3": {
                                "urlVariable": "URL_VARIABLE",
                                "database": true
                            }
                        }
                    },
                    "config": {"URL_VARIABLE": "http://test1.test"},
                    "database": true
                }""",
                createEmptyService(),
                createEmptyDeploymentConfig()
            )
        }.errors.first().errors.first().message

        val expectedErrorMessage3 = "The combination of fields specified for the Toxiproxy proxy named badProxy3 is " +
            "not valid. A valid configuration must contain a value for exactly one of the properties urlVariable, " +
            "database, or databaseName, or both the properties serverVariable and portVariable."

        assertThat(errorMessageDefaultDatabase).isEqualTo(expectedErrorMessage3)
    }

    @Test
    fun `Should fail with an error message when a proxy with the database field is enabled while the database config is not simplified`() {

        dbMock()

        val errorMessage = assertThrows<MultiApplicationValidationException> {
            generateResources(
                """{
                    "toxiproxy": {
                        "version": "2.1.3",
                        "proxies": {
                            "proxyWithNamedDb": {"database": true}
                        }
                    },
                    "database": {"dbName": true}
                }""",
                createEmptyService(),
                createEmptyDeploymentConfig()
            )
        }.errors.first().errors.first().message

        val expectedErrorMessage = "It is not possible to set up a Toxiproxy proxy with the \"database\" property " +
            "when the database config is not simplified. Did you mean to use \"databaseName\"?"

        assertThat(errorMessage).isEqualTo(expectedErrorMessage)
    }

    @Test
    fun `Should fail with an error message when a proxy with the databaseName field is enabled while the database config is simplified`() {

        dbMock()

        val errorMessage = assertThrows<MultiApplicationValidationException> {
            generateResources(
                """{
                    "toxiproxy": {
                        "version": "2.1.3",
                        "proxies": {
                            "proxyWithDefaultDb": {"databaseName": "dbName"}
                        }
                    },
                    "database": true
                }""",
                createEmptyService(),
                createEmptyDeploymentConfig()
            )
        }.errors.first().errors.first().message

        val expectedErrorMessage = "Found named database(s) in the Toxiproxy config, although the database config is " +
            "simplified. Did you mean to use the property \"database\" instead of \"databaseName\"?"

        assertThat(errorMessage).isEqualTo(expectedErrorMessage)
    }

    @Test
    fun `Should fail with an error message when there are several proxies with the database property`() {

        dbMock()

        val errorMessage = assertThrows<MultiApplicationValidationException> {
            generateResources(
                """{
                    "toxiproxy": {
                        "version": "2.1.3",
                        "proxies": {
                            "proxy1": {"database": true},
                            "proxy2": {"database": true}
                        }
                    },
                    "database": true
                }""",
                createEmptyService(),
                createEmptyDeploymentConfig()
            )
        }.errors.first().errors.first().message

        val expectedErrorMessage = "The \"database\" property may only be used once in the Toxiproxy config."

        assertThat(errorMessage).isEqualTo(expectedErrorMessage)
    }

    @Test
    fun `Should fail with an error message when the main proxy name is used`() {

        val errorMessage = assertThrows<MultiApplicationValidationException> {
            generateResources(
                """{
                    "toxiproxy": {
                        "version": "2.1.3",
                        "proxies": {
                            "app": {"urlVariable": "TEST"}
                        }
                    },
                    "config": {"TEST": "http://test1.test"}
                }""",
                createEmptyService(),
                createEmptyDeploymentConfig()
            )
        }.errors.first().errors.first().message

        val expectedErrorMessage = "The name \"app\" is reserved for the proxy for incoming calls."

        assertThat(errorMessage).isEqualTo(expectedErrorMessage)
    }

    @Test
    fun `Should fail with an error message when there are url variable duplicates`() {

        val errorMessage = assertThrows<MultiApplicationValidationException> {
            generateResources(
                """{
                    "toxiproxy": {
                        "version": "2.1.3",
                        "proxies": {
                            "proxy1": {"urlVariable": "TEST"},
                            "proxy2": {"urlVariable": "TEST"}
                        }
                    },
                    "config": {"TEST": "http://test1.test"}
                }""",
                createEmptyService(),
                createEmptyDeploymentConfig()
            )
        }.errors.first().errors.first().message

        val expectedErrorMessage = "The url variable \"TEST\" is referred to by several proxies."

        assertThat(errorMessage).isEqualTo(expectedErrorMessage)
    }

    @Test
    fun `Should fail with an error message when there are server and port variable duplicates`() {

        val errorMessage = assertThrows<MultiApplicationValidationException> {
            generateResources(
                """{
                    "toxiproxy": {
                        "version": "2.1.3",
                        "proxies": {
                            "proxy1": {
                                "serverVariable": "SERVER",
                                "portVariable": "PORT"
                            },
                            "proxy2": {
                                "serverVariable": "SERVER",
                                "portVariable": "PORT"
                            }
                        }
                    },
                    "config": {
                        "SERVER": "test1.test",
                        "PORT": 123
                    }
                }""",
                createEmptyService(),
                createEmptyDeploymentConfig()
            )
        }.errors.first().errors.first().message

        val expectedErrorMessage =
            "The server and port variables \"SERVER\" and \"PORT\" are referred to by several proxies."

        assertThat(errorMessage).isEqualTo(expectedErrorMessage)
    }

    @Test
    fun `Should fail with an error message when there are database name duplicates`() {

        dbMock()

        val errorMessage = assertThrows<MultiApplicationValidationException> {
            generateResources(
                """{
                    "toxiproxy": {
                        "version": "2.1.3",
                        "proxies": {
                            "proxy1": {"databaseName": "test"},
                            "proxy2": {"databaseName": "test"}
                        }
                    },
                    "database": {"test": true}
                }""",
                createEmptyService(),
                createEmptyDeploymentConfig()
            )
        }.errors.first().errors.first().message

        val expectedErrorMessage = "The database name \"test\" is referred to by several proxies."

        assertThat(errorMessage).isEqualTo(expectedErrorMessage)
    }
}
