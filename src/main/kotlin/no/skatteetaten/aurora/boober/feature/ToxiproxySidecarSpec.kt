package no.skatteetaten.aurora.boober.feature

import java.net.URI
import java.nio.charset.Charset
import java.util.Base64
import io.fabric8.kubernetes.api.model.Container
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.PortNumbers
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecValidationException
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseSchemaProvisioner
import no.skatteetaten.aurora.boober.utils.UrlParser
import no.skatteetaten.aurora.boober.utils.toMap

private const val FEATURE_NAME = "toxiproxy"

private const val FIRST_PORT_NUMBER = 18000 // The first Toxiproxy port will be set to this number

internal object ToxiproxyField {
    const val database = "$FEATURE_NAME/database"
    const val endpointsFromConfig = "$FEATURE_NAME/endpointsFromConfig"
    const val serverAndPortFromConfig = "$FEATURE_NAME/serverAndPortFromConfig"
    const val version = "$FEATURE_NAME/version"
}

data class ToxiproxyConfig(
    val name: String = "app",
    val listen: String = "0.0.0.0:" + PortNumbers.TOXIPROXY_HTTP_PORT,
    val upstream: String = "0.0.0.0:" + PortNumbers.INTERNAL_HTTP_PORT,
    val enabled: Boolean = true
)

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

internal val AuroraDeploymentSpec.toxiproxyVersion: String?
    get() = featureEnabled(FEATURE_NAME) { this[ToxiproxyField.version] }

internal fun AuroraDeploymentSpec.validateToxiproxy(): List<AuroraDeploymentSpecValidationException> {
    return listOf(
        missingOrInvalidEndpointVariableErrors(),
        missingServerAndPortVariableErrors(),
        missingDbErrors(),
        proxynameDuplicateErrors()
    ).flatten()
}

// Validate that for every endpoint in toxiproxy/endpointsFromConfig, there is a corresponding environment variable
internal fun AuroraDeploymentSpec.missingOrInvalidEndpointVariableErrors() =
    getToxiproxyEndpointEnvVars().mapNotNull { varName ->
        val envVar = this.getOrNull<String>("config/$varName")
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
internal fun AuroraDeploymentSpec.missingServerAndPortVariableErrors(): List<AuroraDeploymentSpecValidationException> {
    return this.extractToxiproxyServersAndPorts().flatMap { serverAdnPortsVars ->
        val portVar = serverAdnPortsVars.portVar
        val serverVar = serverAdnPortsVars.serverVar
        val serverAndPort = listOf("server" to serverVar, "port" to portVar)

        serverAndPort.mapNotNull { (name, value) ->
            val isServerOrPortMissingFromConfig = this.getSubKeyValues("config").none { it == value }

            if (isServerOrPortMissingFromConfig) {
                AuroraDeploymentSpecValidationException(
                    "Found Toxiproxy config for a $name variable named $value, " +
                        "but there is no such environment variable."
                )
            } else null
        }
    }
}

// Validate that for every database in toxiproxy/database, there is a corresponding database in the spec
internal fun AuroraDeploymentSpec.missingDbErrors(): List<AuroraDeploymentSpecValidationException> {
    val toxiproxyDbNames = this.getSubKeyValues("$FEATURE_NAME/database")
        .filter {
            // TODO": this is wrong we need simplified for database and simplified for specific db and enabled
            if (this.isSimplifiedConfig(ToxiproxyField.database)) {
                this[ToxiproxyField.database]
            } else if (this.isSimplifiedConfig("${ToxiproxyField.database}/$it")) {
                this["${ToxiproxyField.database}/$it"]
            } else {
                this["${ToxiproxyField.database}/$it/enabled"]
            }
        }

    return toxiproxyDbNames.mapNotNull { toxiproxyDbName ->
        val dbNames = getSubKeyValues("database")

        if (!dbNames.contains(toxiproxyDbName)) {
            AuroraDeploymentSpecValidationException(
                "Found Toxiproxy config for database named $toxiproxyDbName, " +
                    "but there is no such database configured."
            )
        } else null
    }
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
    this.extractToxiproxyEndpoints()
        .mapIndexedNotNull { i, (proxyname, varname, enabled) ->
            val url: String? = this.getOrNull("config/$varname")

            url?.let {
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
            }
        }

internal fun AuroraDeploymentSpec.serversAndPortsFromConfig(initialPort: Int): List<ToxiproxyConfig> {
    return extractToxiproxyServersAndPorts()
        .mapIndexed { i, (proxyname, serverVar, portVar, enabled) ->
            val upstreamServer: String? = this.getOrNull("config/$serverVar")
            val upstreamPort: String? = this.getOrNull("config/$portVar")

            ToxiproxyConfig(
                name = proxyname,
                listen = "0.0.0.0:${initialPort + i}",
                upstream = "$upstreamServer:$upstreamPort",
                enabled = enabled
            )
        }
}

internal fun AuroraDeploymentSpec.databasesFromSecrets(
    initialPort: Int,
    databaseSchemaProvisioner: DatabaseSchemaProvisioner,
    userDetailsProvider: UserDetailsProvider
): Pair<List<ToxiproxyConfig>, Map<String, Int>> {
    val schemas = findDatabases(this)
        .filter {
            val databaseEnabledField = when {
                isSimplifiedConfig(ToxiproxyField.database) -> ToxiproxyField.database
                isSimplifiedConfig("${ToxiproxyField.database}/${it.name}") -> "${ToxiproxyField.database}/${it.name}"
                else -> "${ToxiproxyField.database}/${it.name}/enabled"
            }
            this[databaseEnabledField]
        }
        .createSchemaRequests(userDetailsProvider, this)
        .mapNotNull { request ->
            val schema = databaseSchemaProvisioner.findSchema(request)

            if (schema != null) {
                request to schema
            } else null
        }

    return schemas.mapIndexed { i, (request, schema) ->
        val dbName = request.details.schemaName
        val proxyname =
            this.getOrNull<String>("${ToxiproxyField.database}/$dbName/proxyname") ?: "database_${schema.id}"

        val port = initialPort + i
        val toxiproxyConfig = ToxiproxyConfig(
            name = proxyname,
            listen = "0.0.0.0:$port",
            upstream = schema.databaseInstance.host + ":" + schema.databaseInstance.port,
            enabled = this.getOrNull("${ToxiproxyField.database}/$dbName/initialEnabledState") ?: true
        )

        val secretNameToPortMap = mapOf(request.getSecretName(prefix = name) to port)
        Pair(toxiproxyConfig, secretNameToPortMap)
    }
        .unzip()
        .run { Pair(first, second.toMap()) }
}

internal fun AuroraDeploymentSpec.allToxiproxyConfigsAndSecretNameToPortMap(
    databaseSchemaProvisioner: DatabaseSchemaProvisioner,
    userDetailsProvider: UserDetailsProvider
): Pair<List<ToxiproxyConfig>, Map<String, Int>> {
    var nextPortNumber = FIRST_PORT_NUMBER
    val appToxiproxyConfig = listOf(ToxiproxyConfig())
    val endpointsFromConfig = this.endpointsFromConfig(nextPortNumber)
    nextPortNumber = endpointsFromConfig.getNextPortNumber(numberIfEmpty = nextPortNumber)
    val serversAndPortsFromConfig = this.serversAndPortsFromConfig(nextPortNumber)
    nextPortNumber = serversAndPortsFromConfig.getNextPortNumber(numberIfEmpty = nextPortNumber)
    val (databases, secretNameToPortMap) = this.databasesFromSecrets(
        nextPortNumber, databaseSchemaProvisioner, userDetailsProvider
    )

    return listOf(
        appToxiproxyConfig, endpointsFromConfig, serversAndPortsFromConfig, databases
    ).flatten() to secretNameToPortMap
}

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

// Return a list of proxynames and corresponding environment variable names
// If proxyname is not set, it defaults to "endpoint_<variable name>"
private fun AuroraDeploymentSpec.extractToxiproxyEndpoints(): List<ToxiproxyEndpoint> {
    val toxiproxyEndpointConfigNames = getSubKeyValues(ToxiproxyField.endpointsFromConfig)

    return toxiproxyEndpointConfigNames.filter { name ->
        if (this.isSimplifiedConfig("${ToxiproxyField.endpointsFromConfig}/$name")) {
            this["${ToxiproxyField.endpointsFromConfig}/$name"]
        } else {
            this["${ToxiproxyField.endpointsFromConfig}/$name/enabled"]
        }
    }.map { configField ->
        val proxyName =
            this.getOrNull("${ToxiproxyField.endpointsFromConfig}/$configField/proxyname") ?: "endpoint_$configField"

        ToxiproxyEndpoint(
            proxyName = proxyName,
            varName = configField,
            enabled = this["${ToxiproxyField.endpointsFromConfig}/$configField/initialEnabledState"]
        )
    }
}

private fun AuroraDeploymentSpec.extractToxiproxyServersAndPorts(): List<ToxiproxyServerAndPortVars> {
    val serverAndPortNames = this.getSubKeyValues(ToxiproxyField.serverAndPortFromConfig)

    return serverAndPortNames.map { name ->
        ToxiproxyServerAndPortVars(
            proxyname = name,
            serverVar = this["${ToxiproxyField.serverAndPortFromConfig}/$name/serverVariable"],
            portVar = this["${ToxiproxyField.serverAndPortFromConfig}/$name/portVariable"],
            enabled = this["${ToxiproxyField.serverAndPortFromConfig}/$name/initialEnabledState"]
        )
    }
}

private fun AuroraDeploymentSpec.getToxiproxyNames(): List<String> {
    return getToxiproxyEndpointOrDbNames(ToxiproxyField.database) +
        getToxiproxyEndpointOrDbNames(ToxiproxyField.endpointsFromConfig) +
        getToxiproxyServerAndPortNames()
}

private fun AuroraDeploymentSpec.getToxiproxyServerAndPortNames(): List<String> =
    this.findSubKeys(ToxiproxyField.serverAndPortFromConfig).toList()

// Find all proxy names of a given url source (endpoints or database)
private fun AuroraDeploymentSpec.getToxiproxyEndpointOrDbNames(fieldName: String): List<String> {
    val envVars = this.getSubKeyValues(fieldName)
    val defaultPrefix = if (fieldName == ToxiproxyField.database) "database" else "endpoint"
    return envVars.map {
        this.getOrNull("$fieldName/$it/proxyname") ?: "${defaultPrefix}_$it"
    }
}

private fun List<ToxiproxyConfig>.findPortByProxyName(proxyName: String) =
    find { it.name == proxyName }?.listen?.substringAfter(':')

private fun String.convertToProxyUrl(port: Int): String =
    UrlParser(this)
        .withModifiedHostName("localhost")
        .withModifiedPort(port)
        .makeString()

internal fun AuroraDeploymentSpec.getToxiproxyEndpointEnvVars(): Set<String> =
    this.findSubKeys(ToxiproxyField.endpointsFromConfig)
