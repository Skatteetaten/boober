package no.skatteetaten.aurora.boober.feature

import no.skatteetaten.aurora.boober.model.AuroraConfigField
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.PortNumbers

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
val varNameInFieldNameRegex = Regex("(?<=^toxiproxy\\/endpoints\\/)([^\\/]+(?=\\/enabled\$|\\/proxyname\$|\$))")
fun findVarNameInFieldName(fieldName: String) = varNameInFieldNameRegex.find(fieldName)!!.value

// Return lists of AuroraConfigFields grouped by environment variable name
fun AuroraDeploymentSpec.groupToxiproxyEndpointFields(): Map<String, List<Map.Entry<String, AuroraConfigField>>> = this
    .getSubKeys("toxiproxy/endpoints")
    .map { it }
    .groupBy { findVarNameInFieldName(it.key) }

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
