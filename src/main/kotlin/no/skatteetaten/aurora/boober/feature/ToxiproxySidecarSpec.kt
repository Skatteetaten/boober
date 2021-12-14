package no.skatteetaten.aurora.boober.feature

import io.fabric8.kubernetes.api.model.Container
import no.skatteetaten.aurora.boober.model.AuroraConfigField
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.PortNumbers
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecValidationException
import no.skatteetaten.aurora.boober.utils.Url

val toxiproxyTypes = listOf("endpoints", "database")

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

// Return lists of AuroraConfigFields grouped by environment variable or db name
fun AuroraDeploymentSpec.groupToxiproxyFields(type: String): Map<String, List<Map.Entry<String, AuroraConfigField>>> = this
    .getSubKeys("toxiproxy/$type")
    .map { it }
    .groupBy { findVarNameInFieldName(type, it.key) }

fun AuroraDeploymentSpec.groupToxiproxyEndpointFields() = groupToxiproxyFields("endpoints")

// Return a list of proxynames and corresponding environment variable names
// If proxyname is not set, it defaults to "endpoint_<variable name>"
fun AuroraDeploymentSpec.extractToxiproxyEndpoints(): List<Pair<String, String>> = this
    .groupToxiproxyEndpointFields()
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

// Find all proxy names of a given type (endpoints or database)
fun AuroraDeploymentSpec.getToxiproxyNames(type: String): List<String> = groupToxiproxyFields(type)
    .map { (varName, fields) ->
        fields
            .find { it.key == "toxiproxy/$type/$varName/proxyname" }
            ?.value
            ?.value<String>()
            ?: generateProxyNameFromVarName(varName, type)
    }

// Find all proxy names
fun AuroraDeploymentSpec.getToxiproxyNames(): List<String> = toxiproxyTypes.flatMap { getToxiproxyNames(it) }

// Generate a default proxy name based on the variable name
// To be used when there is no proxy name specified by the user
fun generateProxyNameFromVarName(varName: String, type: String): String {
    val prefix = if (type == "endpoints") "endpoint" else "database"
    return "${prefix}_$varName"
}

fun List<ToxiProxyConfig>.findPortByProxyName(proxyName: String) =
    find { it.name == proxyName }?.listen?.substringAfter(':')

fun String.convertToProxyUrl(port: Int): String =
    Url(this)
        .withModifiedHostName("localhost")
        .withModifiedPort(port)
        .makeString()

fun List<Container>.overrideEnvVarsWithProxies(adc: AuroraDeploymentSpec, context: FeatureContext) = forEach {
    adc
        .extractToxiproxyEndpoints()
        .map { (proxyName, varName) -> Pair(proxyName, it.env.find { v -> v.name == varName }) }
        .filterNot { (_, envVar) -> envVar == null }
        .forEach { (proxyName, envVar) ->
            envVar!!.value = envVar.value.convertToProxyUrl(context.toxiproxyConfigs.findPortByProxyName(proxyName)!!.toInt())
        }
}

// Validate that for every endpoint in toxiproxy/endpoints, there is a corresponding environment variable with a valid URL
fun AuroraDeploymentSpec.missingOrInvalidVariableErrors() = groupToxiproxyEndpointFields()
    .keys
    .mapNotNull { varName ->
        val envVar = this.getSubKeys("config")["config/$varName"]?.value<String>()
        if (envVar == null) {
            AuroraDeploymentSpecValidationException(
                "Found Toxiproxy config for endpoint named $varName, " +
                    "but there is no such environment variable."
            )
        } else if (!Url(envVar).isValid()) {
            AuroraDeploymentSpecValidationException(
                "The format of the URL \"$envVar\" given by the config variable $varName is not supported."
            )
        } else null
    }

// Validate that for every database in toxiproxy/database, there is a corresponding database in the spec
fun AuroraDeploymentSpec.missingDbErrors() = groupToxiproxyFields("database")
    .filter { (key, value) -> value.find { it.key == "toxiproxy/database/$key/enabled" }?.value?.value() ?: false }
    .keys
    .mapNotNull { varName ->
        if (!getSubKeyValues("database").contains(varName)) {
            AuroraDeploymentSpecValidationException(
                "Found Toxiproxy config for database named $varName, " +
                    "but there is no such database configured."
            )
        } else null
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
