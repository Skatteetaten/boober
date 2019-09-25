package no.skatteetaten.aurora.boober.feature

import io.fabric8.kubernetes.api.model.Container
import no.skatteetaten.aurora.boober.mapper.ApplicationPlatform
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.mapper.PortNumbers
import org.springframework.stereotype.Service

@Service
class JavaDeployFeature(dockerRegistry: String) : AbstractDeployFeature(dockerRegistry) {
    override fun enable(platform: ApplicationPlatform) = platform == ApplicationPlatform.java

    override fun createContainers(adc: AuroraDeploymentSpec): List<Container> {
        return listOf(createContainer(adc, "${adc.name}-java", mapOf(
                "http" to PortNumbers.INTERNAL_HTTP_PORT,
                "management" to PortNumbers.INTERNAL_ADMIN_PORT,
                "jolokia" to PortNumbers.JOLOKIA_HTTP_PORT)
        ))
    }
}