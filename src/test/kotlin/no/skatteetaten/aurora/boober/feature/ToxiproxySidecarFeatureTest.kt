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
}
