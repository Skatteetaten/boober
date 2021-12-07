package no.skatteetaten.aurora.boober.feature

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.api.model.Service
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.facade.json
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
            .auroraResourceModifiedByThisFeatureWithComment(
                comment = "Changed targetPort to point to toxiproxy",
                featureIndex = 2
            )

        val service = serviceResource.resource as Service
        assertThat(service.spec.ports.first().targetPort).isEqualTo(IntOrString(PortNumbers.TOXIPROXY_HTTP_PORT))

        assertThat(dcResource)
            .auroraResourceModifiedByThisFeatureWithComment(
                comment = "Added toxiproxy volume and sidecar container",
                sourceIndex = 1,
                featureIndex = 2
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
                    "endpoints": {
                        "TEST_WITH_PROXYNAME": {"proxyname": "test1", "enabled": true},
                        "TEST_WITHOUT_PROXYNAME": true,
                        "DISABLED_TEST_WITH_PROXYNAME": {"proxyname": "test3", "enabled": false},
                        "DISABLED_TEST_WITHOUT_PROXYNAME": false,
                        "HTTPS_URL": {"proxyname": "test5", "enabled": true},
                        "URL_WITH_PORT": {"proxyname": "test6", "enabled": true},
                        "URL_WITH_PATH": {"proxyname": "test7", "enabled": true}
                    }
                },
                "config": {
                    "TEST_WITH_PROXYNAME": "http://test1.test",
                    "TEST_WITHOUT_PROXYNAME": "http://test2.test",
                    "DISABLED_TEST_WITH_PROXYNAME": "http://test3.test",
                    "DISABLED_TEST_WITHOUT_PROXYNAME": "http://test4.test",
                    "HTTPS_URL": "https://test5.test",
                    "URL_WITH_PORT": "http://test6.test:1234",
                    "URL_WITH_PATH": "http://test7.test/path"
                }
            }""",
            createEmptyService(),
            createEmptyDeploymentConfig()
        )

        assertThat(serviceResource)
            .auroraResourceModifiedByThisFeatureWithComment(
                comment = "Changed targetPort to point to toxiproxy",
                featureIndex = 2
            )

        val service = serviceResource.resource as Service
        assertThat(service.spec.ports.first().targetPort).isEqualTo(IntOrString(PortNumbers.TOXIPROXY_HTTP_PORT))

        assertThat(dcResource)
            .auroraResourceModifiedByThisFeatureWithComment(
                comment = "Added toxiproxy volume and sidecar container",
                sourceIndex = 1,
                featureIndex = 2
            )
            .auroraResourceMatchesFile("dcWithEndpointMapping.json")

        assertThat(configResource)
            .auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("configWithEndpointMapping.json")
    }

    @Test
    fun `Should fail with an error message when an endpoint with no corresponding environment variable is given`() {

        val errorMessage = assertThrows<MultiApplicationValidationException> {
            generateResources(
                """{
                    "toxiproxy": {
                        "version": "2.1.3",
                        "endpoints": {
                            "NOT_EXISTING_VAR": {"proxyname": "test", "enabled": true}
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
                        "endpoints": {
                            "SOME_VAR": {"proxyname": "test", "enabled": true}
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
    fun `Should fail with an error message when there are proxyname duplicates`() {

        httpMockServer(5000) {
            rule {
                json(
                    DbApiEnvelope("ok", listOf(createDbhSchema()))
                )
            }
        }

        val errorMessage = assertThrows<MultiApplicationValidationException> {
            generateResources(
                """{
                    "toxiproxy": {
                        "version": "2.1.3",
                        "endpoints": {
                            "TEST_WITH_PROXYNAME": {"proxyname": "duplicate", "enabled": true},
                            "TEST_WITH_SAME_PROXYNAME": {"proxyname": "duplicate", "enabled": true}
                        },
                        "database": {"db": {"proxyname": "duplicate", "enabled": true}}
                    },
                    "database": {"db": {"enabled": true}},
                    "config": {
                        "TEST_WITH_PROXYNAME": "http://test1.test",
                        "TEST_WITH_SAME_PROXYNAME": "http://test2.test"
                    }
                }""",
                createEmptyService(),
                createEmptyDeploymentConfig()
            )
        }.errors.first().errors.first().message

        val expectedErrorMessage =
            "Found 3 Toxiproxy configs with the proxy name \"duplicate\". Proxy names have to be unique."

        assertThat(errorMessage).isEqualTo(expectedErrorMessage)
    }

    @Test
    fun `Should map database secret to Toxiproxy`() {

        httpMockServer(5000) {
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

        val (serviceResource, dcResource, secretResource, configResource) = generateResources(
            """{
                "toxiproxy": {"database": true},
                "database": true
            }""",
            mutableSetOf(createEmptyService(), createEmptyDeploymentConfig()),
            createdResources = 2
        )

        assertThat(serviceResource)
            .auroraResourceModifiedByThisFeatureWithComment(
                comment = "Changed targetPort to point to toxiproxy",
                featureIndex = 2
            )

        assertThat(dcResource)
            .auroraResourceModifiedByThisFeatureWithComment(
                comment = "Added toxiproxy volume and sidecar container",
                sourceIndex = 2,
                featureIndex = 2
            )

        assertThat(secretResource)
            .auroraResourceModifiedByThisFeatureWithComment(
                comment = "Changed JDBC URL to point to Toxiproxy",
                featureIndex = 2
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
    fun `Should map multiple database secrets to Toxiproxy`() {

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
                "toxiproxy": {"database": true},
                "database": {
                    "db1": {"enabled": true},
                    "db2": {"enabled": true}
                }
            }""",
            mutableSetOf(createEmptyService(), createEmptyDeploymentConfig()),
            createdResources = 3
        )

        assertThat(serviceResource)
            .auroraResourceModifiedByThisFeatureWithComment(
                comment = "Changed targetPort to point to toxiproxy",
                featureIndex = 2
            )

        assertThat(dcResource)
            .auroraResourceModifiedByThisFeatureWithComment(
                comment = "Added toxiproxy volume and sidecar container",
                sourceIndex = 2,
                featureIndex = 2
            )

        assertThat(secretResource1)
            .auroraResourceModifiedByThisFeatureWithComment(
                comment = "Changed JDBC URL to point to Toxiproxy",
                featureIndex = 2
            )

        assertThat(secretResource2)
            .auroraResourceModifiedByThisFeatureWithComment(
                comment = "Changed JDBC URL to point to Toxiproxy",
                featureIndex = 2
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
            .auroraResourceMatchesFile("configWithMultipleDatabasesMapping.json")
    }

    @Test
    fun `Should map database secrets to named Toxiproxies`() {

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
                    "database": {
                        "db1": {
                            "enabled": true,
                            "proxyname": "proxy1"
                        },
                        "db2": {
                            "enabled": true,
                            "proxyname": "proxy2"
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
            .auroraResourceModifiedByThisFeatureWithComment(
                comment = "Changed targetPort to point to toxiproxy",
                featureIndex = 2
            )

        assertThat(dcResource)
            .auroraResourceModifiedByThisFeatureWithComment(
                comment = "Added toxiproxy volume and sidecar container",
                sourceIndex = 2,
                featureIndex = 2
            )

        assertThat(secretResource1)
            .auroraResourceModifiedByThisFeatureWithComment(
                comment = "Changed JDBC URL to point to Toxiproxy",
                featureIndex = 2
            )

        assertThat(secretResource2)
            .auroraResourceModifiedByThisFeatureWithComment(
                comment = "Changed JDBC URL to point to Toxiproxy",
                featureIndex = 2
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
                        "database": {"db1": true}
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
}
