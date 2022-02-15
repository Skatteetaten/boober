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
import java.net.URI
import java.nio.charset.Charset
import java.util.Base64

enum class ToxiproxyUrlSource(val propName: String, val defaultProxyNamePrefix: String) {
    CONFIG_VAR("endpointsFromConfig", "endpoint"),
    DB_SECRET("database", "database")
}

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

// Regex for matching a variable name in a field name
fun varNameInFieldNameRegex(urlSource: ToxiproxyUrlSource) =
    Regex("(?<=^toxiproxy\\/${urlSource.propName}\\/)([^\\/]+(?=\\/enabled\$|\\/proxyname\$|\\/initialEnabledState\$|\$))")

fun findVarNameInFieldName(urlSource: ToxiproxyUrlSource, fieldName: String) =
    varNameInFieldNameRegex(urlSource).find(fieldName)!!.value

// Return lists of AuroraConfigFields grouped by environment variable or db name
fun AuroraDeploymentSpec.groupToxiproxyFields(urlSource: ToxiproxyUrlSource): Map<String, List<Map.Entry<String, AuroraConfigField>>> =
    getSubKeys("toxiproxy/${urlSource.propName}")
        .map { it }
        .groupBy { findVarNameInFieldName(urlSource, it.key) }

fun AuroraDeploymentSpec.groupToxiproxyEndpointFields() = groupToxiproxyFields(ToxiproxyUrlSource.CONFIG_VAR)

// Return a list of proxynames and corresponding environment variable names
// If proxyname is not set, it defaults to "endpoint_<variable name>"
fun AuroraDeploymentSpec.extractToxiproxyEndpoints(): List<ToxiproxyEndpoint> {
    val propPath = "toxiproxy/" + ToxiproxyUrlSource.CONFIG_VAR.propName
    return groupToxiproxyEndpointFields()
        .filter { (varName, fields) ->
            fields.find { it.key == "$propPath/$varName" }!!.value.value() &&
                fields.find { it.key == "$propPath/$varName/enabled" }!!.value.value()
        }
        .map { (varName, fields) ->
            val proxyName = fields
                .find { it.key == "$propPath/$varName/proxyname" }!!
                .value
                .value<String>()
            val enabled = fields
                .find { it.key == "$propPath/$varName/initialEnabledState" }!!
                .value
                .value<Boolean>()
            ToxiproxyEndpoint(proxyName, varName, enabled)
        }
}

// Generate a default proxy name based on the variable name
// To be used when there is no proxy name specified by the user
fun generateProxyNameFromVarName(varName: String, urlSource: ToxiproxyUrlSource): String =
    "${urlSource.defaultProxyNamePrefix}_$varName"

fun List<ToxiproxyConfig>.findPortByProxyName(proxyName: String) =
    find { it.name == proxyName }?.listen?.substringAfter(':')

fun String.convertToProxyUrl(port: Int): String =
    UrlParser(this)
        .withModifiedHostName("localhost")
        .withModifiedPort(port)
        .makeString()

fun MutableMap<String, String>.convertEncryptedJdbcUrlToEncryptedProxyUrl(toxiproxyPort: Int) {
    this["jdbcurl"] = this["jdbcurl"]
        .let(Base64.getDecoder()::decode)
        .toString(Charset.defaultCharset())
        .convertToProxyUrl(toxiproxyPort)
        .toByteArray()
        .let(Base64.getEncoder()::encodeToString)
}

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

fun List<Container>.overrideEnvVarsWithProxies(adc: AuroraDeploymentSpec, context: FeatureContext) = forEach {
    adc
        .extractToxiproxyEndpoints()
        .map { (proxyName, varName) -> Pair(proxyName, it.env.find { v -> v.name == varName }) }
        .filterNot { (_, envVar) -> envVar == null }
        .forEach { (proxyName, envVar) ->
            envVar!!.value = envVar.value.convertToProxyUrl(context.toxiproxyConfigs.findPortByProxyName(proxyName)!!.toInt())
        }
    adc.extractToxiproxyServersAndPorts().forEach { (proxyName, serverVar, portVar) ->
        it.env.find { v -> v.name == serverVar }?.value = "localhost"
        it.env.find { v -> v.name == portVar }?.value = context.toxiproxyConfigs.findPortByProxyName(proxyName)
    }
}

// Find all proxy names of a given url source (endpoints or database)
fun AuroraDeploymentSpec.getToxiproxyEndpointOrDbNames(urlSource: ToxiproxyUrlSource): List<String> =
    groupToxiproxyFields(urlSource).map { (varName, fields) ->
        fields
            .find { it.key == "toxiproxy/${urlSource.propName}/$varName/proxyname" }
            ?.value
            ?.value<String>()
            ?: generateProxyNameFromVarName(varName, urlSource)
    }

fun AuroraDeploymentSpec.getToxiproxyServerAndPortNames(): List<String> =
    groupToxiproxyServerAndPortFields().keys.toList()

fun AuroraDeploymentSpec.getToxiproxyNames(): List<String> =
    enumValues<ToxiproxyUrlSource>().flatMap { getToxiproxyEndpointOrDbNames(it) } + getToxiproxyServerAndPortNames()

// Validate that for every endpoint in toxiproxy/endpointsFromConfig, there is a corresponding environment variable
fun AuroraDeploymentSpec.missingOrInvalidEndpointVariableErrors() =
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

// Validate that for every database in toxiproxy/database, there is a corresponding database in the spec
fun AuroraDeploymentSpec.missingDbErrors() = groupToxiproxyFields(ToxiproxyUrlSource.DB_SECRET)
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

fun AuroraDeploymentSpec.databasesFromSecrets(
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
    .run { Pair(first, second.fold(emptyMap()) { acc, map -> acc + map }) }

fun List<ToxiproxyConfig>.getNextPortNumber(numberIfEmpty: Int) =
    maxOfOrNull { it.listen.substringAfter(':').toInt() }.let { if (it == null) numberIfEmpty else it + 1 }
