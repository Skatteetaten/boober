package no.skatteetaten.aurora.boober.feature

import no.skatteetaten.aurora.boober.model.AuroraConfigField
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.PortNumbers
import no.skatteetaten.aurora.boober.utils.Url

val AuroraDeploymentSpec.toxiproxyVersion: String?
    get() =
        this.featureEnabled("toxiproxy") {
            this["toxiproxy/version"]
        }

data class ToxiProxyConfig(val name: String, val listen: String, val upstream: String)

fun getDefaultToxiProxyConfig() = ToxiProxyConfig(
    name = "app",
    listen = "0.0.0.0:" + PortNumbers.TOXIPROXY_HTTP_PORT,
    upstream = "0.0.0.0:" + PortNumbers.INTERNAL_HTTP_PORT
)

// Regex for matching a variable name in a field name
fun varNameInFieldNameRegex(type: String) =
    Regex("(?<=^toxiproxy\\/$type\\/)([^\\/]+(?=\\/enabled\$|\\/proxyname\$|\$))")

fun findVarNameInFieldName(type: String, fieldName: String) =
    varNameInFieldNameRegex(type).find(fieldName)!!.value

// Return lists of AuroraConfigFields grouped by environment variable name
fun AuroraDeploymentSpec.groupToxiproxyEndpointFields(): Map<String, List<Map.Entry<String, AuroraConfigField>>> = this
    .getSubKeys("toxiproxy/endpoints")
    .map { it }
    .groupBy { findVarNameInFieldName("endpoints", it.key) }

// Return a list of proxynames and corresponding environment variable names
// If proxyname is not set, it defaults to "endpoint_<variable name>"
fun AuroraDeploymentSpec.extractToxiproxyEndpoints(): List<Pair<String, String>> = this
    .groupToxiproxyEndpointFields()
    .filter { (varName, fields) ->
        fields.find { it.key == "toxiproxy/endpoints/$varName" }!!.value.value() &&
            fields.find { it.key == "toxiproxy/endpoints/$varName/enabled" }!!.value.value()
    }
    .map { (varName, fields) ->
        val proxyName = fields
            .find { it.key == "toxiproxy/endpoints/$varName/proxyname" }!!
            .value
            .value<String>()
        Pair(proxyName, varName)
    }

// Generate a default proxy name based on the variable name
// To be used when there is no proxy name specified by the user
fun generateProxyNameFromVarName(varName: String) = "endpoint_$varName"

// Search for the toxiproxy port number by a given proxy name
fun MutableList<ToxiProxyConfig>.findPortByProxyName(proxyName: String) = this
    .find { it.name == proxyName }
    ?.listen
    ?.substringAfter(':')

fun String.convertToProxyUrl(port: Int): String =
    Url(this)
        .modifyHostName("localhost")
        .modifyPort(port)
        .makeString()
