package no.skatteetaten.aurora.boober.feature

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.newContainer
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.EnvVarSource
import io.fabric8.kubernetes.api.model.IntOrString
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
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DbhSchema
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DbhUser
import no.skatteetaten.aurora.boober.utils.AbstractMultiFeatureTest
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.HttpMock
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.httpMockServer
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.web.client.RestTemplateBuilder
import java.util.UUID

class ToxiproxySidecarFeatureTest : AbstractMultiFeatureTest() {

    val provisioner = DatabaseSchemaProvisioner(
        DbhRestTemplateWrapper(RestTemplateBuilder().build(), "http://localhost:5000", 0),
        jacksonObjectMapper()
    )

    override val features: List<Feature>
        get() = listOf(
            ConfigFeature(),
            ToxiproxySidecarFeature(cantusService, provisioner, userDetailsProvider, "2.1.3", "utv")
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
                featureIndex = 1
            )

        val service = serviceResource.resource as Service
        assertThat(service.spec.ports.first().targetPort).isEqualTo(IntOrString(PortNumbers.TOXIPROXY_HTTP_PORT))

        assertThat(dcResource)
            .auroraResourceModifiedByThisFeatureWithComment(
                comment = "Added toxiproxy volume and sidecar container",
                sourceIndex = 1,
                featureIndex = 1
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
                featureIndex = 1
            )

        val service = serviceResource.resource as Service
        assertThat(service.spec.ports.first().targetPort).isEqualTo(IntOrString(PortNumbers.TOXIPROXY_HTTP_PORT))

        assertThat(dcResource)
            .auroraResourceModifiedByThisFeatureWithComment(
                comment = "Added toxiproxy volume and sidecar container",
                sourceIndex = 1,
                featureIndex = 1
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
    fun `Should fail with an error message when there are proxyname duplicates`() {

        val errorMessage = assertThrows<MultiApplicationValidationException> {
            generateResources(
                """{
                    "toxiproxy": {
                        "version": "2.1.3",
                        "endpoints": {
                            "TEST_WITH_PROXYNAME": {"proxyname": "duplicate", "enabled": true},
                            "TEST_WITH_SAME_PROXYNAME": {"proxyname": "duplicate", "enabled": true}
                        }
                    },
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
            "Found 2 Toxiproxy configs with the proxy name \"duplicate\". Proxy names have to be unique."

        assertThat(errorMessage).isEqualTo(expectedErrorMessage)
    }

    @Test
    fun databasetest() {

        httpMockServer(5000) {
            rule {
                json(
                    DbApiEnvelope(
                        "ok",
                        listOf(
                            DbhSchema(
                                id = UUID.randomUUID().toString(),
                                type = "SCHEMA",
                                databaseInstance = DatabaseSchemaInstance(1512, "testhost"),
                                jdbcUrl = "foo/bar/baz",
                                labels = mapOf(
                                    "affiliation" to "paas",
                                    "name" to "testname"
                                ),
                                users = listOf(DbhUser("username", "password", type = "SCHEMA"))
                            )
                        )
                    )
                )
            }
        }

        val (serviceResource, dcResource, configResource) = generateResources(
            """{
                "toxiproxy": {
                    "database": true
                },
                "database": true
            }""",
            createEmptyService(),
            createDeploymentConfigWithContainer(
                newContainer {
                    name = "simple"
                    env = listOf(
                        EnvVar("DB", "/u01/secrets/app/testname-db/info", EnvVarSource()),
                        EnvVar("DB_PROPERTIES", "/u01/secrets/app/testname-db/db.properties", EnvVarSource())
                    )
                }
            )
        )

        assertThat(dcResource)
            .auroraResourceModifiedByThisFeatureWithComment("Added toxiproxy volume and sidecar container")
            .auroraResourceMatchesFile("dcWithDatabaseMapping.json")

        assertThat(configResource)
            .auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("configWithDatabaseMapping.json")
    }
}
