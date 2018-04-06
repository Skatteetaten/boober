package no.skatteetaten.aurora.boober.mapper.v1

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfigResource
import no.skatteetaten.aurora.boober.model.Probe

data class ToxiProxyConfig(val name: String, val listen: String, val upstream: String)

// TODO Sjekke korrekt syntax for konstanter i Kotlin

class ToxiProxyDefaults {
    companion object {
        val NAME = "toxiproxy"
        val LISTEN_PORT = 8090
        val UPSTREAM_PORT = 8080
        val ADMIN_PORT = 8474
        val READINESS_PROBE = Probe(port = 8090, delay = 10, timeout = 1)
        val LIVENESS_PROBE = null
        val RESOURCE_LIMIT = AuroraDeploymentConfigResource(cpu = "1", memory = "256Mi")
        val RESOURCE_REQUEST = AuroraDeploymentConfigResource(cpu = "100m", memory = "128Mi")
    }
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
        listen = "0.0.0.0:" + ToxiProxyDefaults.LISTEN_PORT,
        upstream = "0.0.0.0:" + ToxiProxyDefaults.UPSTREAM_PORT)

    return jacksonObjectMapper().writeValueAsString(listOf(config))
}



