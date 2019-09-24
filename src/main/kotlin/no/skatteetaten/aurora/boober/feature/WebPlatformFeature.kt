package no.skatteetaten.aurora.boober.feature

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentContext
import no.skatteetaten.aurora.boober.service.Feature

class WebPlatformFeature() : Feature {
    override fun handlers(header: AuroraDeploymentContext): Set<AuroraConfigFieldHandler> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    /*
    fun createContainers(baseContainer: AuroraContainer): List<AuroraContainer> {
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
    }*/
}