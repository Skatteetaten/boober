package no.skatteetaten.aurora.boober.platform

import com.fkorotkov.kubernetes.containerPort
import com.fkorotkov.kubernetes.envVar
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.Container
import no.skatteetaten.aurora.boober.utils.addIfNotNull

class WebPlattformHandler : AbstractHandler() {
    override val container: List<Container>
        get() = listOf(
                Container("node", listOf(
                        containerPort {
                            containerPort = 9090
                            protocol = "TCP"
                            name = "http"
                        },
                        containerPort {
                            containerPort = 8081
                            protocol = "TCP"
                            name = "management"
                        }),
                        listOf("/u01/bin/run_node"),
                        env = listOf(
                                envVar {
                                    name = "HTTP_PORT"
                                    value = "9090"
                                },
                                envVar {
                                    name = "MANAGEMENT_HTTP_PORT"
                                    value = "8081"
                                }
                        )
                ),
                Container("nginx", listOf(
                        containerPort {
                            containerPort = 8080
                            protocol = "TCP"
                            name = "http"
                        }),
                        listOf("/u01/bin/run_nginx"),
                        env = listOf(
                                envVar {
                                    name = "HTTP_PORT"
                                    value = "8080"
                                }
                        )
                )
        )

    override fun handlers(handlers: Set<AuroraConfigFieldHandler>): Set<AuroraConfigFieldHandler> {

        val buildHandlers = handlers.find { it.name.startsWith("baseImage") }?.let {
            setOf(AuroraConfigFieldHandler("baseImage/name", defaultValue = "wrench"),
                    AuroraConfigFieldHandler("baseImage/version", defaultValue = "0"))
        }
        return handlers.addIfNotNull(buildHandlers)

    }
}
