package no.skatteetaten.aurora.boober.feature

import io.fabric8.kubernetes.api.model.Container
import no.skatteetaten.aurora.boober.model.AuroraConfigField
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.PortNumbers
import org.springframework.web.util.UriComponentsBuilder

val AuroraDeploymentSpec.toxiproxyVersion: String?
    get() =
        this.featureEnabled("toxiproxy") {
            this["toxiproxy/version"]
        }

data class ToxiProxyConfig(val name: String, val listen: String, val upstream: String)

data class ToxiproxyServerAndPortVars(val proxyname: String, val serverVar: String, val portVar: String)

fun getDefaultToxiProxyConfig() = ToxiProxyConfig(
    name = "app",
    listen = "0.0.0.0:" + PortNumbers.TOXIPROXY_HTTP_PORT,
    upstream = "0.0.0.0:" + PortNumbers.INTERNAL_HTTP_PORT
)

// Regex for matching a variable name in an endpoint field name
val varNameInEndpointFieldNameRegex =
    Regex("(?<=^toxiproxy\\/endpointsFromConfig\\/)([^\\/]+(?=\\/enabled\$|\\/proxyname\$|\$))")

fun findVarNameInEndpointFieldName(fieldName: String) = varNameInEndpointFieldNameRegex.find(fieldName)!!.value

// Return lists of AuroraConfigFields grouped by environment variable name
fun AuroraDeploymentSpec.groupToxiproxyEndpointFields(): Map<String, List<Map.Entry<String, AuroraConfigField>>> =
    getSubKeys("toxiproxy/endpointsFromConfig")
        .map { it }
        .groupBy { findVarNameInEndpointFieldName(it.key) }

// Return a list of proxynames and corresponding environment variable names
// If proxyname is not set, it defaults to "endpoint_<variable name>"
fun AuroraDeploymentSpec.extractToxiproxyEndpoints(): List<Pair<String, String>> = groupToxiproxyEndpointFields()
    .filter { (varName, fields) ->
        fields.find { it.key == "toxiproxy/endpointsFromConfig/$varName" }!!.value.value() &&
            fields.find { it.key == "toxiproxy/endpointsFromConfig/$varName/enabled" }!!.value.value()
    }
    .map { (varName, fields) ->
        val proxyName = fields
            .find { it.key == "toxiproxy/endpointsFromConfig/$varName/proxyname" }!!
            .value
            .value<String>()
        Pair(proxyName, varName)
    }

// Generate a default proxy name based on the variable name
// To be used when there is no proxy name specified by the user
fun generateProxyNameFromVarName(varName: String) = "endpoint_$varName"

// Regex for matching a proxy name in a server and port field name
val proxyNameInServerAndPortFieldNameRegex =
    Regex("(?<=^toxiproxy\\/serverAndPortFromConfig\\/)([^\\/]+(?=\\/serverVariable\$|\\/portVariable\$|\$))")

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
            fields.find { it.key == "toxiproxy/serverAndPortFromConfig/$proxyName/portVariable" }!!.value.value()
        )
    }

fun List<ToxiProxyConfig>.findPortByProxyName(proxyName: String) =
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
