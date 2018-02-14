package no.skatteetaten.aurora.boober.mapper.platform

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.Container
import no.skatteetaten.aurora.boober.utils.addIfNotNull

class WebPlatformHandler : AbstractPlatformHandler() {

    override val container: List<Container>
        get() = listOf(
                Container("node",
                        mapOf("http" to 9090, "management" to 8081),
                        listOf("/u01/bin/run_node")),
                Container("nginx", mapOf("http" to 8080),
                        listOf("/u01/bin/run_nginx"))

        )

    override fun handlers(handlers: Set<AuroraConfigFieldHandler>): Set<AuroraConfigFieldHandler> {

        val buildHandlers = handlers.find { it.name.startsWith("baseImage") }?.let {
            setOf(AuroraConfigFieldHandler("baseImage/name", defaultValue = "wrench"),
                    AuroraConfigFieldHandler("baseImage/version", defaultValue = "0"))
        }
        return handlers.addIfNotNull(buildHandlers)

    }
}
