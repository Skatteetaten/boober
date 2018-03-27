package no.skatteetaten.aurora.boober.service.internal

import no.skatteetaten.aurora.boober.mapper.platform.AuroraContainer
import no.skatteetaten.aurora.boober.model.*

fun buildToxiProxyContainer(auroraDeploymentSpec: AuroraDeploymentSpec, mounts: List<Mount>?): AuroraContainer {
    return AuroraContainer(
            name = "${auroraDeploymentSpec.name}-toxiproxy",
            tcpPorts = mapOf("http" to 8090, "management" to 8474),
            readiness = Probe(port = 8090, delay = 10, timeout = 1),
            liveness = Probe(port = 8090, delay = 10, timeout = 1),
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
            content = mapOf("config.json" to "[{\"name\":\"app\",\"listen\":\"0.0.0.0:8090\",\"upstream\": \"0.0.0.0:8080\"}]"),
            targetContainer = "toxiproxy"))
}






