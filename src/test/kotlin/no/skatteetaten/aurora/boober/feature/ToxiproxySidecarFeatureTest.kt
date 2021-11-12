package no.skatteetaten.aurora.boober.feature

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.fkorotkov.kubernetes.newContainer
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.EnvVarSource
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.Service
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.model.PortNumbers
import no.skatteetaten.aurora.boober.service.CantusService
import no.skatteetaten.aurora.boober.service.ImageMetadata
import no.skatteetaten.aurora.boober.service.MultiApplicationValidationException
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.HttpMock
import org.junit.jupiter.api.assertThrows

class ToxiproxySidecarFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = ToxiproxySidecarFeature(cantusService, "2.1.3")

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
            createEmptyService(), createEmptyDeploymentConfig()
        )

        assertThat(serviceResource).auroraResourceModifiedByThisFeatureWithComment("Changed targetPort to point to toxiproxy")
        val service = serviceResource.resource as Service
        assertThat(service.spec.ports.first().targetPort).isEqualTo(IntOrString(PortNumbers.TOXIPROXY_HTTP_PORT))

        assertThat(dcResource).auroraResourceModifiedByThisFeatureWithComment("Added toxiproxy volume and sidecar container")
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
            createDeploymentConfigWithContainer(newContainer {
                name = "simple"
                env = listOf(
                        EnvVar("TEST_WITH_PROXYNAME", "http://test1.test", EnvVarSource()),
                        EnvVar("TEST_WITHOUT_PROXYNAME", "http://test2.test", EnvVarSource()),
                        EnvVar("DISABLED_TEST_WITH_PROXYNAME", "http://test3.test", EnvVarSource()),
                        EnvVar("DISABLED_TEST_WITHOUT_PROXYNAME", "http://test4.test", EnvVarSource()),
                        EnvVar("HTTPS_URL", "https://test5.test", EnvVarSource()),
                        EnvVar("URL_WITH_PORT", "http://test6.test:1234", EnvVarSource()),
                        EnvVar("URL_WITH_PATH", "http://test7.test/path", EnvVarSource())
                )
            })
        )

        assertThat(serviceResource)
            .auroraResourceModifiedByThisFeatureWithComment("Changed targetPort to point to toxiproxy")

        val service = serviceResource.resource as Service
        assertThat(service.spec.ports.first().targetPort).isEqualTo(IntOrString(PortNumbers.TOXIPROXY_HTTP_PORT))

        assertThat(dcResource)
            .auroraResourceModifiedByThisFeatureWithComment("Added toxiproxy volume and sidecar container")
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
                createDeploymentConfigWithContainer(newContainer {
                    name = "simple"
                    env = listOf(
                            EnvVar("TEST_WITH_PROXYNAME", "http://test1.test", EnvVarSource()),
                            EnvVar("TEST_WITH_SAME_PROXYNAME", "http://test2.test", EnvVarSource())
                    )
                })
            )
        }.errors.first().errors.first().message

        val expectedErrorMessage =
            "Found 2 Toxiproxy configs with the proxy name \"duplicate\". Proxy names have to be unique."

        assertThat(errorMessage).isEqualTo(expectedErrorMessage)
    }
}
