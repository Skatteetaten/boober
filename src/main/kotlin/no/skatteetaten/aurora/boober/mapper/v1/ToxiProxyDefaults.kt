package no.skatteetaten.aurora.boober.mapper.v1

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.fabric8.kubernetes.api.model.EnvVar
import no.skatteetaten.aurora.boober.mapper.v1.ToxiProxyDefaults.TOXIPROXY_REPOSITORY
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfigResource
import no.skatteetaten.aurora.boober.model.Probe

data class ToxiProxyConfig(val name: String, val listen: String, val upstream: String)

// TODO Sjekke korrekt syntax for konstanter i Kotlin

object ToxiProxyDefaults {

    const val NAME = "toxiproxy"
    const val TOXIPROXY_REPOSITORY = "shopify/toxiproxy"

    val LIVENESS_PROBE = null
    val READINESS_PROBE = Probe(port = PortNumbers.TOXIPROXY_HTTP_PORT, delay = 10, timeout = 1)

    val RESOURCE_LIMIT = AuroraDeploymentConfigResource(cpu = "1", memory = "256Mi")
    val RESOURCE_REQUEST = AuroraDeploymentConfigResource(cpu = "100m", memory = "128Mi")

    val ARGS: List<String> = listOf("-config", "/u01/config/config.json")
    val ENV: List<EnvVar> = emptyList()
}

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
