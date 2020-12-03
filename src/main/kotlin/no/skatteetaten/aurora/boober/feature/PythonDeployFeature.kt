package no.skatteetaten.aurora.boober.feature

import io.fabric8.kubernetes.api.model.Container
import no.skatteetaten.aurora.boober.feature.ApplicationPlatform.python
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.PortNumbers.EXTRA_APPLICATION_PORT
import no.skatteetaten.aurora.boober.model.PortNumbers.INTERNAL_ADMIN_PORT
import no.skatteetaten.aurora.boober.model.PortNumbers.INTERNAL_HTTP_PORT
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class PythonDeployFeature(
    @Value("\${integrations.docker.registry}") val registry: String
) : AbstractDeployFeature(registry) {
    override fun enable(platform: ApplicationPlatform) = platform == python

    override fun createContainers(adc: AuroraDeploymentSpec): List<Container> = listOf(
        createContainer(
            adc = adc,
            containerName = "${adc.name}-${adc.applicationPlatform.name}",
            containerPorts = mapOf(
                "http" to INTERNAL_HTTP_PORT,
                "management" to INTERNAL_ADMIN_PORT,
                "extra" to EXTRA_APPLICATION_PORT
            )
        )
    )
}
