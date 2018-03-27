package no.skatteetaten.aurora.boober.service.internal

import no.skatteetaten.aurora.boober.mapper.platform.AuroraContainer
import no.skatteetaten.aurora.boober.mapper.platform.AuroraServicePort
import no.skatteetaten.aurora.boober.model.*

fun buildToxiProxyContainer(auroraDeploymentSpec: AuroraDeploymentSpec, mounts: List<Mount>?): AuroraContainer {

    val config = auroraDeploymentSpec.deploy?.toxiProxy!!

    return AuroraContainer(
            name = "${auroraDeploymentSpec.name}-toxiproxy",
            tcpPorts = mapOf("http" to config.listen, "management" to config.management),
            readiness = Probe(port = config.listen, delay = 10, timeout = 1),
            liveness = Probe(port = config.listen, delay = 10, timeout = 1),
            limit = AuroraDeploymentConfigResource(cpu = "1", memory = "256Mi"),
            request = AuroraDeploymentConfigResource(cpu = "100m", memory = "128Mi"),
            env = emptyMap(),
            mounts = mounts?.filter { it.targetContainer.equals("toxiproxy") },
            shouldHaveImageChange = false,
            args = listOf("-config", "/u01/config/config.json")
    )
}

fun createToxiProxyMounts(deploymentSpec: AuroraDeploymentSpec): List<Mount> {
    return listOf(Mount(path = "/u01/config",
            type = MountType.ConfigMap,
            mountName = "toxiproxy-volume",
            volumeName = "toxiproxy-config",
            exist = false,
            content = mapOf("config.json" to getConfigAsString()),
            targetContainer = "toxiproxy"))
}

fun createToxiProxyServicePorts(config: ToxiProxy): List<AuroraServicePort> {
    return listOf(
            AuroraServicePort(name = "http", port = 80, targetPort = config.listen),
            AuroraServicePort(name = "management", port = config.management, targetPort = config.management)
    )
}

private fun getConfigAsString(): String { // TODO !!
    return "[{\"name\":\"app\",\"listen\":\"0.0.0.0:8090\",\"upstream\": \"0.0.0.0:8080\"}]"
}






