package no.skatteetaten.aurora.boober.feature

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import assertk.assertThat
import assertk.assertions.isEqualTo
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.Service
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.model.PortNumbers
import no.skatteetaten.aurora.boober.service.CantusService
import no.skatteetaten.aurora.boober.service.ImageMetadata
import no.skatteetaten.aurora.boober.service.MultiApplicationValidationException
import no.skatteetaten.aurora.boober.utils.AbstractMultiFeatureTest
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.HttpMock
import org.junit.jupiter.api.assertThrows

class ToxiproxySidecarFeatureTest : AbstractMultiFeatureTest() {
    override val features: List<Feature>
        get() = listOf(
            ConfigFeature(),
            ToxiproxySidecarFeature(cantusService, "2.1.3")
        )

    private val cantusService: CantusService = mockk()

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
                    "endpointsFromConfig": {
                        "TEST_WITH_PROXYNAME": {"proxyname": "test1", "enabled": true},
                        "TEST_WITHOUT_PROXYNAME": true,
                        "DISABLED_TEST_WITH_PROXYNAME": {"proxyname": "test3", "enabled": false},
                        "DISABLED_TEST_WITHOUT_PROXYNAME": false,
                        "HTTPS_URL": {"proxyname": "test5", "enabled": true},
                        "URL_WITH_PORT": {"proxyname": "test6", "enabled": true},
                        "URL_WITH_PATH": {"proxyname": "test7", "enabled": true},
                        "INITIALLY_ENABLED": {"proxyname": "test8", "enabled": true, "initialEnabledState": true},
                        "INITIALLY_DISABLED": {"proxyname": "test9", "enabled": true, "initialEnabledState": false}
                    }
                },
                "config": {
                    "TEST_WITH_PROXYNAME": "http://test1.test",
                    "TEST_WITHOUT_PROXYNAME": "http://test2.test",
                    "DISABLED_TEST_WITH_PROXYNAME": "http://test3.test",
                    "DISABLED_TEST_WITHOUT_PROXYNAME": "http://test4.test",
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
    fun `Should add toxiproxy to dc and map servers and ports from container`() {

        val (serviceResource, dcResource, configResource) = generateResources(
            """{
                "toxiproxy": {
                    "version": "2.1.3",
                    "serverAndPortFromConfig": {
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
                        "endpointsFromConfig": {
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
    fun `Should fail with an error message when serverVarible points to an inexistent variable`() {

        val errorMessage = assertThrows<MultiApplicationValidationException> {
            generateResources(
                """{
                    "toxiproxy": {
                        "version": "2.1.3",
                        "serverAndPortFromConfig": {
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

        val errorMessage = assertThrows<MultiApplicationValidationException> {
            generateResources(
                """{
                    "toxiproxy": {
                        "version": "2.1.3",
                        "serverAndPortFromConfig": {
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
        }.errors.first().errors.first().message

        val expectedErrorMessage =
            "Found Toxiproxy config for a port variable named INEXISTENT_PORT_VAR, but there is no such environment variable."

        assertThat(errorMessage).isEqualTo(expectedErrorMessage)
    }

    @Test
    fun `Should fail with an error message when there are proxyname duplicates`() {

        val errorMessage = assertThrows<MultiApplicationValidationException> {
            generateResources(
                """{
                    "toxiproxy": {
                        "version": "2.1.3",
                        "endpointsFromConfig": {
                            "TEST_WITH_PROXYNAME": {"proxyname": "duplicate", "enabled": true},
                            "TEST_WITH_SAME_PROXYNAME": {"proxyname": "duplicate", "enabled": true}
                        },
                        "serverAndPortFromConfig": {
                            "duplicate": {
                                "serverVariable": "SAME_PROXYNAME_SERVER",
                                "portVariable": "SAME_PROXYNAME_PORT"
                            }
                        }
                    },
                    "config": {
                        "TEST_WITH_PROXYNAME": "http://test1.test",
                        "TEST_WITH_SAME_PROXYNAME": "http://test2.test",
                        "SAME_PROXYNAME_SERVER": "testserver.test",
                        "SAME_PROXYNAME_PORT": 123
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
}
