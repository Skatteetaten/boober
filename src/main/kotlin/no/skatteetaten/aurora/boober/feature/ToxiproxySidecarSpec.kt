package no.skatteetaten.aurora.boober.feature

import io.fabric8.kubernetes.api.model.Container
import no.skatteetaten.aurora.boober.model.AuroraConfigField
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.PortNumbers
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecValidationException
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseSchemaProvisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaForAppRequest
import no.skatteetaten.aurora.boober.utils.UrlParser
import no.skatteetaten.aurora.boober.utils.toMap
import java.net.URI
import java.nio.charset.Charset
import java.util.Base64

enum class ToxiproxyUrlSource(val propName: String, val defaultProxyNamePrefix: String) {
    CONFIG_VAR("endpointsFromConfig", "endpoint"),
    DB_SECRET("database", "database")
}

data class ToxiproxyConfig(
    val name: String = "app",
    val listen: String = "0.0.0.0:" + PortNumbers.TOXIPROXY_HTTP_PORT,
    val upstream: String = "0.0.0.0:" + PortNumbers.INTERNAL_HTTP_PORT,
    val enabled: Boolean = true
)

internal val AuroraDeploymentSpec.toxiproxyVersion: String?
    get() = featureEnabled("toxiproxy") { this["toxiproxy/version"] }

// Find the variable name in a field name
internal fun findVarNameInFieldName(urlSource: ToxiproxyUrlSource, fieldName: String): String =
    Regex("(?<=^toxiproxy\\/${urlSource.propName}\\/)([^\\/]+(?=\\/enabled\$|\\/proxyname\$|\\/initialEnabledState\$|\$))")
        .find(fieldName)!!
        .value

// Find the proxy name in a server and port field name
internal fun findProxyNameInServerAndPortFieldName(fieldName: String) =
    Regex("(?<=^toxiproxy\\/serverAndPortFromConfig\\/)([^\\/]+(?=\\/serverVariable\$|\\/portVariable\$|\\/initialEnabledState\$|\$))")
        .find(fieldName)!!
        .value

// Generate a default proxy name based on the variable name
// To be used when there is no proxy name specified by the user
internal fun generateProxyNameFromVarName(varName: String, urlSource: ToxiproxyUrlSource): String =
    "${urlSource.defaultProxyNamePrefix}_$varName"

// Validate that for every endpoint in toxiproxy/endpointsFromConfig, there is a corresponding environment variable
internal fun AuroraDeploymentSpec.missingOrInvalidEndpointVariableErrors() =
    groupToxiproxyEndpointFields().keys.mapNotNull { varName ->
        val envVar = this.getSubKeys("config")["config/$varName"]?.value<String>()
        if (envVar == null) {
            AuroraDeploymentSpecValidationException(
                "Found Toxiproxy config for endpoint named $varName, " +
                    "but there is no such environment variable."
            )
        } else if (!UrlParser(envVar).isValid()) {
            AuroraDeploymentSpecValidationException(
                "The format of the URL \"$envVar\" given by the config variable $varName is not supported."
            )
        } else null
    }

// Validate that the environment variables given in toxiproxy/serverAndPortFromConfig exist
internal fun AuroraDeploymentSpec.missingServerAndPortVariableErrors() =
    groupToxiproxyServerAndPortFields().flatMap { (proxyName, fields) ->
        listOf("server", "port").mapNotNull { serverOrPort ->

            val serverOrPortVariable =
                fields.findValue<String>("toxiproxy/serverAndPortFromConfig/$proxyName/${serverOrPort}Variable")!!

            if (getSubKeys("config").keys.none { it.removePrefix("config/") == serverOrPortVariable }) {
                AuroraDeploymentSpecValidationException(
                    "Found Toxiproxy config for a $serverOrPort variable named $serverOrPortVariable, " +
                        "but there is no such environment variable."
                )
            } else null
        }
    }

// Validate that for every database in toxiproxy/database, there is a corresponding database in the spec
internal fun AuroraDeploymentSpec.missingDbErrors() = groupToxiproxyFields(ToxiproxyUrlSource.DB_SECRET)
    .filter { (key, fields) -> fields.findValue("toxiproxy/database/$key/enabled") ?: false }
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
internal fun AuroraDeploymentSpec.proxynameDuplicateErrors() = getToxiproxyNames()
    .groupingBy { it }
    .eachCount()
    .filter { it.value > 1 }
    .map {
        AuroraDeploymentSpecValidationException(
            "Found ${it.value} Toxiproxy configs with the proxy name \"${it.key}\". " +
                "Proxy names have to be unique."
        )
    }

internal fun AuroraDeploymentSpec.endpointsFromConfig(initialPort: Int) =
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

internal fun AuroraDeploymentSpec.serversAndPortsFromConfig(initialPort: Int) =
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

internal fun AuroraDeploymentSpec.databasesFromSecrets(
    initialPort: Int,
    databaseSchemaProvisioner: DatabaseSchemaProvisioner,
    userDetailsProvider: UserDetailsProvider
): Pair<List<ToxiproxyConfig>, Map<String, Int>> = findDatabases(this)
    .filter {
        fields["toxiproxy/database"]?.value?.booleanValue() == true ||
            fields["toxiproxy/database/" + it.name + "/enabled"]?.value?.booleanValue() == true
    }
    .createSchemaRequests(userDetailsProvider, this)
    .associateWith { databaseSchemaProvisioner.findSchema(it) }
    .filterNot { it.value == null }
    .toList()
    .mapIndexed { i, (request, schema) ->
        val dbName = (request as SchemaForAppRequest).labels["name"]
        val proxyname = fields["toxiproxy/database/$dbName/proxyname"]?.value<String>() ?: "database_${schema!!.id}"
        val enabled = fields["toxiproxy/database/$dbName/initialEnabledState"]?.value<Boolean>() ?: true
        val port = initialPort + i
        val toxiproxyConfig = ToxiproxyConfig(
            name = proxyname,
            listen = "0.0.0.0:$port",
            upstream = schema!!.databaseInstance.host + ":" + schema.databaseInstance.port,
            enabled = enabled
        )
        val secretNameToPortMap = mapOf(request.getSecretName(prefix = name) to port)
        Pair(toxiproxyConfig, secretNameToPortMap)
    }
    .unzip()
    .run { Pair(first, second.toMap()) }

internal fun MutableMap<String, String>.convertEncryptedJdbcUrlToEncryptedProxyUrl(toxiproxyPort: Int) {
    this["jdbcurl"] = this["jdbcurl"]
        .let(Base64.getDecoder()::decode)
        .toString(Charset.defaultCharset())
        .convertToProxyUrl(toxiproxyPort)
        .toByteArray()
        .let(Base64.getEncoder()::encodeToString)
}

internal fun List<Container>.overrideEnvVarsWithProxies(adc: AuroraDeploymentSpec, context: FeatureContext) = forEach {
    adc
        .extractToxiproxyEndpoints()
        .map { (proxyName, varName) -> Pair(proxyName, it.env.find { v -> v.name == varName }) }
        .filterNot { (_, envVar) -> envVar == null }
        .forEach { (proxyName, envVar) ->
            envVar!!.value = envVar
                .value
                .convertToProxyUrl(context.toxiproxyConfigs.findPortByProxyName(proxyName)!!.toInt())
        }
    adc.extractToxiproxyServersAndPorts().forEach { (proxyName, serverVar, portVar) ->
        it.env.find { v -> v.name == serverVar }?.value = "localhost"
        it.env.find { v -> v.name == portVar }?.value = context.toxiproxyConfigs.findPortByProxyName(proxyName)
    }
}

internal fun List<ToxiproxyConfig>.getNextPortNumber(numberIfEmpty: Int) =
    maxOfOrNull { it.listen.substringAfter(':').toInt() }.let { if (it == null) numberIfEmpty else it + 1 }

private data class ToxiproxyEndpoint(
    val proxyName: String,
    val varName: String,
    val enabled: Boolean
)

private data class ToxiproxyServerAndPortVars(
    val proxyname: String,
    val serverVar: String,
    val portVar: String,
    val enabled: Boolean
)

// Return lists of AuroraConfigFields grouped by environment variable or db name
private fun AuroraDeploymentSpec.groupToxiproxyFields(urlSource: ToxiproxyUrlSource) =
    getSubKeys("toxiproxy/${urlSource.propName}")
        .entries
        .groupBy { findVarNameInFieldName(urlSource, it.key) }

private fun AuroraDeploymentSpec.groupToxiproxyEndpointFields() = groupToxiproxyFields(ToxiproxyUrlSource.CONFIG_VAR)

// Return a list of proxynames and corresponding environment variable names
// If proxyname is not set, it defaults to "endpoint_<variable name>"
private fun AuroraDeploymentSpec.extractToxiproxyEndpoints(): List<ToxiproxyEndpoint> {
    val propPath = "toxiproxy/" + ToxiproxyUrlSource.CONFIG_VAR.propName
    return groupToxiproxyEndpointFields()
        .filter { (varName, fields) ->
            fields.findValue("$propPath/$varName")!! &&
                fields.findValue("$propPath/$varName/enabled")!!
        }
        .map { (varName, fields) ->
            ToxiproxyEndpoint(
                proxyName = fields.findValue<String>("$propPath/$varName/proxyname")!!,
                varName = varName,
                enabled = fields.findValue<Boolean>("$propPath/$varName/initialEnabledState")!!
            )
        }
}

// Return lists of AuroraConfigFields grouped by proxy name
private fun AuroraDeploymentSpec.groupToxiproxyServerAndPortFields(): Map<String, List<Map.Entry<String, AuroraConfigField>>> =
    getSubKeys("toxiproxy/serverAndPortFromConfig")
        .map { it }
        .groupBy { findProxyNameInServerAndPortFieldName(it.key) }

private fun AuroraDeploymentSpec.extractToxiproxyServersAndPorts(): List<ToxiproxyServerAndPortVars> =
    groupToxiproxyServerAndPortFields().map { (proxyName, fields) ->
        ToxiproxyServerAndPortVars(
            proxyName,
            fields.findValue("toxiproxy/serverAndPortFromConfig/$proxyName/serverVariable")!!,
            fields.findValue("toxiproxy/serverAndPortFromConfig/$proxyName/portVariable")!!,
            fields.findValue("toxiproxy/serverAndPortFromConfig/$proxyName/initialEnabledState")!!
        )
    }

// Find all proxy names of a given url source (endpoints or database)
private fun AuroraDeploymentSpec.getToxiproxyEndpointOrDbNames(urlSource: ToxiproxyUrlSource): List<String> =
    groupToxiproxyFields(urlSource).map { (varName, fields) ->
        fields.findValue<String>("toxiproxy/${urlSource.propName}/$varName/proxyname")
            ?: generateProxyNameFromVarName(varName, urlSource)
    }

private fun AuroraDeploymentSpec.getToxiproxyServerAndPortNames(): List<String> =
    groupToxiproxyServerAndPortFields().keys.toList()

private fun AuroraDeploymentSpec.getToxiproxyNames(): List<String> =
    enumValues<ToxiproxyUrlSource>().flatMap { getToxiproxyEndpointOrDbNames(it) } + getToxiproxyServerAndPortNames()

private fun List<ToxiproxyConfig>.findPortByProxyName(proxyName: String) =
    find { it.name == proxyName }?.listen?.substringAfter(':')

private fun String.convertToProxyUrl(port: Int): String =
    UrlParser(this)
        .withModifiedHostName("localhost")
        .withModifiedPort(port)
        .makeString()

private inline fun <reified T> List<Map.Entry<String, AuroraConfigField>>.findValue(key: String) =
    find { it.key == key }?.value?.value<T>()
