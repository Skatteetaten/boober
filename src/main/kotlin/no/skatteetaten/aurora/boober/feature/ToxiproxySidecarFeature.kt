package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.Probe
import no.skatteetaten.aurora.boober.feature.ToxiProxyDefaults.TOXIPROXY_REPOSITORY
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentContext
import no.skatteetaten.aurora.boober.mapper.PortNumbers
import no.skatteetaten.aurora.boober.service.Feature

class ToxiproxySidecarFeature() : Feature {
    override fun handlers(header: AuroraDeploymentContext): Set<AuroraConfigFieldHandler> {
        return setOf(
                AuroraConfigFieldHandler("toxiproxy", defaultValue = false, canBeSimplifiedConfig = true),
                AuroraConfigFieldHandler("toxiproxy/version", defaultValue = "2.1.3")
        )
    }

    private fun createToxiProxyMounts(deploymentSpecInternal: AuroraDeploymentContext): List<Mount> {
/*
        return deploymentSpecInternal.deploy?.toxiProxy?.let {
            listOf(
                    Mount(
                            path = "/u01/config",
                            type = MountType.ConfigMap,
                            mountName = "${ToxiProxyDefaults.NAME}-volume",
                            volumeName = "${ToxiProxyDefaults.NAME}-config",
                            exist = false,
                            content = mapOf("config.json" to getToxiProxyConfig()),
                            targetContainer = ToxiProxyDefaults.NAME
                    )
            )
        }
                .orEmpty()
                */
        return emptyList()
    }

    fun getToxiProxy(auroraDeploymentSpec: AuroraDeploymentContext, name: String): ToxiProxy? {
        return auroraDeploymentSpec.featureEnabled(name) {
            ToxiProxy(auroraDeploymentSpec["$it/version"])
        }
    }

    /*
    fun createSidecarContainers(
            auroraDeploymentSpecInternal: AuroraDeploymentSpecInternal,
            mounts: List<Mount>?
    ): List<AuroraContainer>? {

        return auroraDeploymentSpecInternal.deploy?.toxiProxy?.let {
            listOf(
                    AuroraContainer(
                            name = "${auroraDeploymentSpecInternal.name}-toxiproxy",
                            tcpPorts = mapOf(
                                    "http" to PortNumbers.TOXIPROXY_HTTP_PORT,
                                    "management" to PortNumbers.TOXIPROXY_ADMIN_PORT
                            ),
                            readiness = ToxiProxyDefaults.READINESS_PROBE,
                            liveness = ToxiProxyDefaults.LIVENESS_PROBE,
                            limit = ToxiProxyDefaults.RESOURCE_LIMIT,
                            request = ToxiProxyDefaults.RESOURCE_REQUEST,
                            env = ToxiProxyDefaults.ENV,
                            mounts = mounts,
                            shouldHaveImageChange = false,
                            args = ToxiProxyDefaults.ARGS,
                            image = getToxiProxyImage(it.version)
                    )
            )
        }
    }

     */
}


data class ToxiProxy(
        val version: String
)

data class ToxiProxyConfig(val name: String, val listen: String, val upstream: String)

object ToxiProxyDefaults {

    const val NAME = "toxiproxy"
    const val TOXIPROXY_REPOSITORY = "shopify/toxiproxy"

    val LIVENESS_PROBE = null
    val READINESS_PROBE = Probe(port = PortNumbers.TOXIPROXY_HTTP_PORT, delay = 10, timeout = 1)

    val RESOURCE_LIMIT = AuroraDeploymentConfigResource(cpu = "1", memory = "256Mi")
    val RESOURCE_REQUEST = AuroraDeploymentConfigResource(cpu = "10m", memory = "128Mi")

    val ARGS: List<String> = listOf("-config", "/u01/config/config.json")
    val ENV: List<EnvVar> = emptyList()
}

data class AuroraDeploymentConfigResources(
        val limit: AuroraDeploymentConfigResource,
        val request: AuroraDeploymentConfigResource
)

data class AuroraDeploymentConfigResource(
        val cpu: String,
        val memory: String
)

data class Probe(val path: String? = null, val port: Int, val delay: Int, val timeout: Int)

fun getToxiProxyImage(version: String): String {
    return TOXIPROXY_REPOSITORY + ":" + version
}

fun getToxiProxyConfig(): String {
    val config = ToxiProxyConfig(
            name = "app",
            listen = "0.0.0.0:" + PortNumbers.TOXIPROXY_HTTP_PORT,
            upstream = "0.0.0.0:" + PortNumbers.INTERNAL_HTTP_PORT)

    return jacksonObjectMapper().writeValueAsString(listOf(config))
}