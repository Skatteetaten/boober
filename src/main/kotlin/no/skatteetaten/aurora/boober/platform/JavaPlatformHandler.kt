package no.skatteetaten.aurora.boober.platform

import com.fkorotkov.kubernetes.containerPort
import com.fkorotkov.kubernetes.envVar
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.Container
import no.skatteetaten.aurora.boober.utils.addIfNotNull

class JavaPlatformHandler : AbstractHandler() {
    override val container: List<Container>
        get() = listOf(Container("java", listOf(
                containerPort {
                    containerPort = 8080
                    protocol = "TCP"
                    name = "http"
                },
                containerPort {
                    containerPort = 8081
                    protocol = "TCP"
                    name = "management"
                },
                containerPort {
                    containerPort = 8778
                    protocol = "TCP"
                    name = "jolokia"
                }),
                env = listOf(
                        envVar {
                            name = "HTTP_PORT"
                            value = "8080"
                        },
                        envVar {
                            name = "MANAGEMENT_HTTP_PORT"
                            value = "8081"
                        }
                )))

    override fun handlers(handlers: Set<AuroraConfigFieldHandler>): Set<AuroraConfigFieldHandler> {


        val buildHandlers = handlers.find { it.path.startsWith("/baseImage") }?.let {
            setOf(
                    AuroraConfigFieldHandler("baseImage/name", defaultValue = "flange"),
                    AuroraConfigFieldHandler("baseImage/version", defaultValue = "8")
            )
        }

        return handlers.addIfNotNull(buildHandlers)

    }
}

