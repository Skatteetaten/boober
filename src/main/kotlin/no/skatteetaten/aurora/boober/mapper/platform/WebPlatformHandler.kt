package no.skatteetaten.aurora.boober.mapper.platform

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.v1.PortNumbers
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.model.TemplateType.development
import no.skatteetaten.aurora.boober.utils.oneOf
import org.springframework.stereotype.Component

@Component
class WebPlatformHandler : ApplicationPlatformHandler("web") {
    override fun createContainers(baseContainer: AuroraContainer): List<AuroraContainer> {
        return listOf(
            baseContainer.copy(
                name = "${baseContainer.name}-node",
                args = listOf("/u01/bin/run_node"),
                tcpPorts = mapOf("http" to PortNumbers.NODE_PORT, "management" to PortNumbers.INTERNAL_ADMIN_PORT)
            ),
            baseContainer.copy(
                name = "${baseContainer.name}-nginx",
                args = listOf("/u01/bin/run_nginx"),
                tcpPorts = mapOf("http" to PortNumbers.INTERNAL_HTTP_PORT)
            )
        )
    }

    override fun handlers(type: TemplateType): Set<AuroraConfigFieldHandler> {

        val typeHandlers = when (type) {

            development -> setOf(
                AuroraConfigFieldHandler("baseImage/name", defaultValue = "wrench8"),
                AuroraConfigFieldHandler("baseImage/version", defaultValue = "1")
            )
            else -> emptySet()

        }
        return typeHandlers +
            AuroraConfigFieldHandler(
                "routeDefaults/tls/insecurePolicy",
                defaultValue = "redirect",
                validator = { it.oneOf(listOf("deny", "allow", "redirect")) })
    }
}
