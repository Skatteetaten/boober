package no.skatteetaten.aurora.boober.mapper.v1

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfigResource
import no.skatteetaten.aurora.boober.model.Probe

data class ToxiProxyConfig(val name: String, val listen: String, val upstream: String)

// TODO Sjekke korrekt syntax for konstanter i Kotlin

object ToxiProxyDefaults {

    val NAME = "toxiproxy"
    val READINESS_PROBE = Probe(port = PortNumbers.TOXIPROXY_HTTP_PORT, delay = 10, timeout = 1)
    val LIVENESS_PROBE = null
    val RESOURCE_LIMIT = AuroraDeploymentConfigResource(cpu = "1", memory = "256Mi")
    val RESOURCE_REQUEST = AuroraDeploymentConfigResource(cpu = "100m", memory = "128Mi")
}

fun getToxiProxyArgs(): List<String> {
    return listOf("-config", "/u01/config/config.json")
}

fun getToxiProxyImage(version: String): String {
    return "shopify/toxiproxy:" + version
}

fun getToxiProxyEnv(): Map<String, String> {
    return emptyMap()
}

fun getToxiProxyConfig(): String {
    val config = ToxiProxyConfig(
        name = "app",
        listen = "0.0.0.0:" + PortNumbers.TOXIPROXY_HTTP_PORT,
        upstream = "0.0.0.0:" + PortNumbers.INTERNAL_HTTP_PORT)

    return jacksonObjectMapper().writeValueAsString(listOf(config))
}



