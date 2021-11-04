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
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.HttpMock

class ToxiproxySidecarFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = ToxiproxySidecarFeature(cantusService)

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
           }""", createEmptyService(), createEmptyDeploymentConfig()
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
                        "TEST_WITH_PROXYNAME": {"proxyname": "test", "enabled": true},
                        "TEST_WITHOUT_PROXYNAME": true,
                        "DISABLED_TEST_WITH_PROXYNAME": {"proxyname": "test", "enabled": false},
                        "DISABLED_TEST_WITHOUT_PROXYNAME": false
                    }
                },
                "config": {
                    "TEST_WITH_PROXYNAME": "http://test1.test",
                    "TEST_WITHOUT_PROXYNAME": "http://test2.test"
                }
            }""",
            createEmptyService(),
            createDeploymentConfigWithContainer(newContainer {
                name = "simple"
                env = listOf(
                    EnvVar("TEST_WITH_PROXYNAME", "http://test1.test", EnvVarSource()),
                    EnvVar("TEST_WITHOUT_PROXYNAME", "http://test2.test", EnvVarSource())
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
}
