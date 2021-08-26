package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.Service
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.model.PortNumbers
import no.skatteetaten.aurora.boober.service.CantusService
import no.skatteetaten.aurora.boober.service.ImageMetadata
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.HttpMock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ClingerSidecarFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = ClingerSidecarFeature(cantusService)

    private val cantusService: CantusService = mockk()

    @BeforeEach
    fun setupMock() {
        every {
            cantusService.getImageMetadata(
                "no_skatteetaten_aurora", "clinger", "0.3.1"
            )
        } returns
                ImageMetadata(
                    "docker.registry/no_skatteetaten_aurora/clinger",
                    "0.3.1",
                    "sha:1234567"
                )
    }

    @AfterEach
    fun clearMocks() {
        HttpMock.clearAllHttpMocks()
    }

    @Test
    fun `should add clinger to dc and change service port`() {
        val (serviceResource, dcResource) = modifyResources(
            """{
             "azure" : {
                "proxySidecar": {
                    "version": "0.3.1", 
                    "discoveryUrl": "https://endpoint",
                    "ivGroupsRequired": "false"
                }
              }
           }""", createEmptyService(), createEmptyDeploymentConfig()
        )

        assertThat(serviceResource).auroraResourceModifiedByThisFeatureWithComment("Changed targetPort to point to clinger")
        val service = serviceResource.resource as Service
        assertEquals(
            service.spec.ports.first().targetPort,
            IntOrString(PortNumbers.CLINGER_PROXY_SERVER_PORT),
            "Target should be rewritten to proxy port."
        )

        assertThat(dcResource).auroraResourceModifiedByThisFeatureWithComment("Added clinger sidecar container")
            .auroraResourceMatchesFile("dc.json")
    }

    @Test
    fun `clinger is able to be enabled based on default values`() {
        val (serviceResource, dcResource) = modifyResources(
            """{
             "azure" : {
                "proxySidecar": {
                    "discoveryUrl": "https://endpoint"
                }
              }
           }""", createEmptyService(), createEmptyDeploymentConfig()
        )

        assertThat(serviceResource).auroraResourceModifiedByThisFeatureWithComment("Changed targetPort to point to clinger")
        val service = serviceResource.resource as Service
        assertEquals(
            service.spec.ports.first().targetPort,
            IntOrString(PortNumbers.CLINGER_PROXY_SERVER_PORT),
            "Target should be rewritten to proxy port."
        )

        assertThat(dcResource).auroraResourceModifiedByThisFeatureWithComment("Added clinger sidecar container")
            .auroraResourceMatchesFile("dc.json")
    }
}
