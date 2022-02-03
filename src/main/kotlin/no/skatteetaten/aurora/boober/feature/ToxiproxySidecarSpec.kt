package no.skatteetaten.aurora.boober.feature

import io.fabric8.kubernetes.api.model.Container
import no.skatteetaten.aurora.boober.model.AuroraConfigField
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.PortNumbers
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecValidationException
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI

val AuroraDeploymentSpec.toxiproxyVersion: String?
    get() =
        this.featureEnabled("toxiproxy") {
            this["toxiproxy/version"]
        }

data class ToxiproxyConfig(
    val name: String = "app",
    val listen: String = "0.0.0.0:" + PortNumbers.TOXIPROXY_HTTP_PORT,
    val upstream: String = "0.0.0.0:" + PortNumbers.INTERNAL_HTTP_PORT,
    val enabled: Boolean = true
)

data class ToxiproxyEndpoint(
    val proxyName: String,
    val varName: String,
    val enabled: Boolean
)

data class ToxiproxyServerAndPortVars(
    val proxyname: String,
    val serverVar: String,
    val portVar: String,
    val enabled: Boolean
)

// Regex for matching a variable name in an endpoint field name
val varNameInEndpointFieldNameRegex =
    Regex("(?<=^toxiproxy\\/endpointsFromConfig\\/)([^\\/]+(?=\\/enabled\$|\\/proxyname\$|\\/initialEnabledState\$|\$))")

fun findVarNameInEndpointFieldName(fieldName: String) = varNameInEndpointFieldNameRegex.find(fieldName)!!.value

// Return lists of AuroraConfigFields grouped by environment variable name
fun AuroraDeploymentSpec.groupToxiproxyEndpointFields(): Map<String, List<Map.Entry<String, AuroraConfigField>>> =
    getSubKeys("toxiproxy/endpointsFromConfig")
        .map { it }
        .groupBy { findVarNameInEndpointFieldName(it.key) }

// Return a list of proxynames and corresponding environment variable names
// If proxyname is not set, it defaults to "endpoint_<variable name>"
fun AuroraDeploymentSpec.extractToxiproxyEndpoints(): List<ToxiproxyEndpoint> = groupToxiproxyEndpointFields()
    .filter { (varName, fields) ->
        fields.find { it.key == "toxiproxy/endpointsFromConfig/$varName" }!!.value.value() &&
            fields.find { it.key == "toxiproxy/endpointsFromConfig/$varName/enabled" }!!.value.value()
    }
    .map { (varName, fields) ->
        val proxyName = fields
            .find { it.key == "toxiproxy/endpointsFromConfig/$varName/proxyname" }!!
            .value
            .value<String>()
        val enabled = fields
            .find { it.key == "toxiproxy/endpointsFromConfig/$varName/initialEnabledState" }!!
            .value
            .value<Boolean>()
        ToxiproxyEndpoint(proxyName, varName, enabled)
    }

// Generate a default proxy name based on the variable name
// To be used when there is no proxy name specified by the user
fun generateProxyNameFromVarName(varName: String) = "endpoint_$varName"

// Regex for matching a proxy name in a server and port field name
val proxyNameInServerAndPortFieldNameRegex =
    Regex("(?<=^toxiproxy\\/serverAndPortFromConfig\\/)([^\\/]+(?=\\/serverVariable\$|\\/portVariable\$|\\/initialEnabledState\$|\$))")

fun findProxyNameInServerAndPortFieldName(fieldName: String) = proxyNameInServerAndPortFieldNameRegex.find(fieldName)!!.value

// Return lists of AuroraConfigFields grouped by proxy name
fun AuroraDeploymentSpec.groupToxiproxyServerAndPortFields(): Map<String, List<Map.Entry<String, AuroraConfigField>>> =
    getSubKeys("toxiproxy/serverAndPortFromConfig")
        .map { it }
        .groupBy { findProxyNameInServerAndPortFieldName(it.key) }

fun AuroraDeploymentSpec.extractToxiproxyServersAndPorts(): List<ToxiproxyServerAndPortVars> =
    groupToxiproxyServerAndPortFields().map { (proxyName, fields) ->
        ToxiproxyServerAndPortVars(
            proxyName,
            fields.find { it.key == "toxiproxy/serverAndPortFromConfig/$proxyName/serverVariable" }!!.value.value(),
            fields.find { it.key == "toxiproxy/serverAndPortFromConfig/$proxyName/portVariable" }!!.value.value(),
            fields.find { it.key == "toxiproxy/serverAndPortFromConfig/$proxyName/initialEnabledState" }!!.value.value()
        )
    }

fun List<ToxiproxyConfig>.findPortByProxyName(proxyName: String) =
    find { it.name == proxyName }?.listen?.substringAfter(':')

fun List<Container>.overrideEnvVarsWithProxies(adc: AuroraDeploymentSpec, context: FeatureContext) = forEach {
    adc
        .extractToxiproxyEndpoints()
        .map { (proxyName, varName) -> Pair(proxyName, it.env.find { v -> v.name == varName }) }
        .filterNot { (_, envVar) -> envVar == null }
        .forEach { (proxyName, envVar) ->
            envVar!!.value = UriComponentsBuilder
                .fromUriString(envVar.value)
                .host("localhost")
                .port(context.toxiproxyConfigs.findPortByProxyName(proxyName))
                .build()
                .toUriString()
        }
    adc.extractToxiproxyServersAndPorts().forEach { (proxyName, serverVar, portVar) ->
        it.env.find { v -> v.name == serverVar }?.value = "localhost"
        it.env.find { v -> v.name == portVar }?.value = context.toxiproxyConfigs.findPortByProxyName(proxyName)
    }
}

fun AuroraDeploymentSpec.getToxiproxyEndpointNames(): List<String> =
    groupToxiproxyEndpointFields().map { (varName, fields) ->
        fields
            .find { it.key == "toxiproxy/endpointsFromConfig/$varName/proxyname" }
            ?.value
            ?.value<String>()
            ?: generateProxyNameFromVarName(varName)
    }

fun AuroraDeploymentSpec.getToxiproxyServerAndPortNames(): List<String> =
    groupToxiproxyServerAndPortFields().keys.toList()

fun AuroraDeploymentSpec.getToxiproxyNames(): List<String> =
    getToxiproxyEndpointNames() + getToxiproxyServerAndPortNames()

// Validate that for every endpoint in toxiproxy/endpointsFromConfig, there is a corresponding environment variable
fun AuroraDeploymentSpec.missingEndpointVariableErrors() =
    groupToxiproxyEndpointFields().keys.mapNotNull { varName ->
        if (getSubKeys("config").keys.none { it.removePrefix("config/") == varName }) {
            AuroraDeploymentSpecValidationException(
                "Found Toxiproxy config for endpoint named $varName, " +
                    "but there is no such environment variable."
            )
        } else null
    }

// Validate that the environment variables given in toxiproxy/serverAndPortFromConfig exist
fun AuroraDeploymentSpec.missingServerAndPortVariableErrors() =
    groupToxiproxyServerAndPortFields().flatMap { (proxyName, fields) ->
        listOf("server", "port").mapNotNull { serverOrPort ->
            val serverOrPortVariable = fields
                .find { it.key == "toxiproxy/serverAndPortFromConfig/$proxyName/${serverOrPort}Variable" }!!
                .value
                .value<String>()
            if (getSubKeys("config").keys.none { it.removePrefix("config/") == serverOrPortVariable }) {
                AuroraDeploymentSpecValidationException(
                    "Found Toxiproxy config for a $serverOrPort variable named $serverOrPortVariable, " +
                        "but there is no such environment variable."
                )
            } else null
        }
    }

// Validate that there are no proxyname duplicates
fun AuroraDeploymentSpec.proxynameDuplicateErrors() = getToxiproxyNames()
    .groupingBy { it }
    .eachCount()
    .filter { it.value > 1 }
    .map {
        AuroraDeploymentSpecValidationException(
            "Found ${it.value} Toxiproxy configs with the proxy name \"${it.key}\". " +
                "Proxy names have to be unique."
        )
    }

fun AuroraDeploymentSpec.endpointsFromConfig(initialPort: Int) =
    extractToxiproxyEndpoints().mapIndexedNotNull { i, (proxyname, varname, enabled) ->
        val url = fields["config/$varname"]?.value<String>()
        if (url != null) {
            val uri = URI(url)
            val upstreamPort = if (uri.port == -1) {
                if (uri.scheme == "https") PortNumbers.HTTPS_PORT else PortNumbers.HTTP_PORT
            } else uri.port
            ToxiproxyConfig(
                name = proxyname,
                listen = "0.0.0.0:${initialPort + i}",
                upstream = uri.host + ":" + upstreamPort,
                enabled = enabled
            )
        } else null
    }

fun AuroraDeploymentSpec.serversAndPortsFromConfig(initialPort: Int) =
    extractToxiproxyServersAndPorts().mapIndexed { i, (proxyname, serverVar, portVar, enabled) ->
        val upstreamServer = fields["config/$serverVar"]?.value<String>()
        val upstreamPort = fields["config/$portVar"]?.value<String>()
        ToxiproxyConfig(
            name = proxyname,
            listen = "0.0.0.0:${initialPort + i}",
            upstream = "$upstreamServer:$upstreamPort",
            enabled = enabled
        )
    }

fun List<ToxiproxyConfig>.getNextPortNumber(numberIfEmpty: Int) =
    maxOfOrNull { it.listen.substringAfter(':').toInt() }.let { if (it == null) numberIfEmpty else it + 1 }