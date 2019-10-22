package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.Service
import no.skatteetaten.aurora.boober.model.PortNumbers
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import org.junit.jupiter.api.Test

class ToxiproxySidecarFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = ToxiproxySidecarFeature()

    @Test
    fun `should add toxiproxy to dc and change service port`() {

        val resources = generateResources("""{
             "toxiproxy" : true 
           }""", createEmptyService(), createEmptyDeploymentConfig())

        assertThat(resources.size).isEqualTo(3)
        val (serviceResource, dcResource, configResource) = resources.toList()

        assertThat(serviceResource).auroraResourceModifiedByThisFeatureWithComment("Changed targetPort to point to toxiproxy")
        val service = serviceResource.resource as Service
        assertThat(service.spec.ports.first().targetPort).isEqualTo(IntOrString(PortNumbers.TOXIPROXY_HTTP_PORT))

        assertThat(dcResource).auroraResourceModifiedByThisFeatureWithComment("Added toxiproxy volume and sidecar container")
            .auroraResourceMatchesFile("dc.json")

        assertThat(configResource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("config.json")
    }
}
