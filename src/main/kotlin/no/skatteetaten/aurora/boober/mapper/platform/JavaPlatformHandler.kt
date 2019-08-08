package no.skatteetaten.aurora.boober.mapper.platform

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.v1.PortNumbers
import no.skatteetaten.aurora.boober.model.InsecurePolicy
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.model.TemplateType.development
import no.skatteetaten.aurora.boober.utils.oneOf
import org.springframework.stereotype.Component

@Component
class JavaPlatformHandler : ApplicationPlatformHandler("java") {
    override fun createContainers(baseContainer: AuroraContainer): List<AuroraContainer> {
        return listOf(
            baseContainer.copy(
                name = "${baseContainer.name}-java",
                tcpPorts = mapOf(
                    "http" to PortNumbers.INTERNAL_HTTP_PORT,
                    "management" to PortNumbers.INTERNAL_ADMIN_PORT,
                    "jolokia" to PortNumbers.JOLOKIA_HTTP_PORT
                )
            )
        )
    }

    override fun handlers(type: TemplateType): Set<AuroraConfigFieldHandler> {

        val typeHandlers = when (type) {

            development -> setOf(
                AuroraConfigFieldHandler("baseImage/name", defaultValue = "wingnut8"),
                AuroraConfigFieldHandler("baseImage/version", defaultValue = "1")
            )
            else -> emptySet()
        }
        return typeHandlers +
            AuroraConfigFieldHandler(
                "routeDefaults/tls/insecurePolicy",
                defaultValue = InsecurePolicy.None,
                validator = { it.oneOf(InsecurePolicy.values().map { v -> v.name }) })
    }
}
