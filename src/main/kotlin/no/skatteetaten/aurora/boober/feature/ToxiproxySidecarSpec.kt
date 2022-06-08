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

internal const val MAIN_PROXY_NAME = "app"

internal object ToxiproxyField {
    const val version = "$FEATURE_NAME/version"
    const val proxies = "$FEATURE_NAME/proxies"
}

data class ToxiproxyConfig(
    val name: String = MAIN_PROXY_NAME,
    val listen: String = "0.0.0.0:" + PortNumbers.TOXIPROXY_HTTP_PORT,
    val upstream: String = "0.0.0.0:" + PortNumbers.INTERNAL_HTTP_PORT,
    val enabled: Boolean = true
)

internal data class ToxiproxyEndpoint(
    val proxyName: String,
    val varName: String,
    val enabled: Boolean
)

internal data class ToxiproxyServerAndPortVars(
    val proxyname: String,
    val serverVar: String?,
    val portVar: String?,
    val enabled: Boolean
)

internal data class ToxiproxyDatabase(
    val proxyName: String,
    val databaseName: String,
    val enabled: Boolean
)

internal data class ToxiproxyProxy(
    val proxyName: String,
    val enabled: Boolean,
    val initialEnabledState: Boolean,
    val urlVariable: String?,
    val serverVariable: String?,
    val portVariable: String?,
    val database: Boolean,
    val databaseName: String?
) {
    fun isEndpointProxy() = urlVariable != null &&
        urlVariable.isNotEmpty() &&
        listOf(serverVariable, portVariable, databaseName).all { it.isNullOrBlank() } &&
        !database

    fun isServerAndPortProxy() = listOf(serverVariable, portVariable).any { it != null && it.isNotBlank() } &&
        listOf(urlVariable, databaseName).all { it.isNullOrBlank() } &&
        !database

    fun isNamedDatabaseProxy() = databaseName != null &&
        databaseName.isNotEmpty() &&
        listOf(urlVariable, serverVariable, portVariable).all { it.isNullOrBlank() } &&
        !database

    fun isDefaultDatabaseProxy() = database &&
        listOf(urlVariable, serverVariable, portVariable, databaseName).all { it.isNullOrBlank() }

    fun toToxiproxyEndpoint() =
        if (isEndpointProxy()) ToxiproxyEndpoint(proxyName, urlVariable!!, initialEnabledState)
        else null

    fun toToxiproxyServerAndPortVars() =
        if (isServerAndPortProxy()) ToxiproxyServerAndPortVars(proxyName, serverVariable, portVariable, initialEnabledState)
        else null

    fun toToxiproxyDatabase(defaultName: String?) =
        if (isNamedDatabaseProxy()) ToxiproxyDatabase(proxyName, databaseName!!, initialEnabledState)
        else if (defaultName != null && defaultName.isNotBlank() && isDefaultDatabaseProxy())
            ToxiproxyDatabase(proxyName, defaultName, initialEnabledState)
        else null

    fun toToxiproxyDatabase() = toToxiproxyDatabase(null)

    fun invalidCombinationError(): AuroraDeploymentSpecValidationException? {
        val validConfigMessage = "A valid configuration must contain a value for exactly one of the properties " +
            "urlVariable, database, or databaseName, or both the properties serverVariable and portVariable."
        return if (hasNoReference()) {
            AuroraDeploymentSpecValidationException(
                "Neither of the fields urlVariable, serverVariable, portVariable, database or databaseName are set " +
                    "for the Toxiproxy proxy named $proxyName. $validConfigMessage"
            )
        } else if (isNotValidProxy()) {
            AuroraDeploymentSpecValidationException(
                "The combination of fields specified for the Toxiproxy proxy named $proxyName is not valid. " +
                    validConfigMessage
            )
        } else null
    }

    private fun hasNoReference() = !database &&
        listOf(urlVariable, serverVariable, portVariable, databaseName).all { it.isNullOrBlank() }

    private fun isNotValidProxy() = listOf(
        isEndpointProxy(),
        isServerAndPortProxy(),
        isNamedDatabaseProxy(),
        isDefaultDatabaseProxy()
    ).all { !it }
}

internal val AuroraDeploymentSpec.toxiproxyVersion: String?
    get() = featureEnabled(FEATURE_NAME) { this[ToxiproxyField.version] }

private fun AuroraDeploymentSpec.endpointsFromConfig(initialPort: Int) =
    extractToxiproxyEndpoints().mapIndexedNotNull { i, (proxyname, varname, enabled) ->
        val url: String? = getOrNull("config/$varname")
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

private fun AuroraDeploymentSpec.serversAndPortsFromConfig(initialPort: Int): List<ToxiproxyConfig> {
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

private typealias SecretNameToPortMap = Map<String, Int>

private fun AuroraDeploymentSpec.databasesFromSecrets(
    initialPort: Int,
    databaseSchemaProvisioner: DatabaseSchemaProvisioner,
    userDetailsProvider: UserDetailsProvider
): Pair<List<ToxiproxyConfig>, SecretNameToPortMap> {

    val toxiproxyDatabases: Map<String, ToxiproxyDatabase> = extractToxiproxyDatabases().associateBy { it.databaseName }

    return findDatabases(this)
        .filter { db -> toxiproxyDatabases.containsKey(db.name) }
        .createSchemaRequests(userDetailsProvider, this)
        .mapNotNull { request ->
            val schema = databaseSchemaProvisioner.findSchema(request)
            if (schema != null) request to schema else null
        }
        .mapIndexed { i, (request, schema) ->
            val dbName = request.details.schemaName
            val toxiproxyDatabase = toxiproxyDatabases[dbName]!!
            val port = initialPort + i
            val toxiproxyConfig = ToxiproxyConfig(
                name = toxiproxyDatabase.proxyName,
                listen = "0.0.0.0:$port",
                upstream = schema.databaseInstance.host + ":" + schema.databaseInstance.port,
                enabled = toxiproxyDatabase.enabled
            )
            val secretNameToPortMap = mapOf(request.getSecretName(prefix = name) to port)
            Pair(toxiproxyConfig, secretNameToPortMap)
        }
        .unzip()
        .run { Pair(first, second.toMap()) }
}

internal fun AuroraDeploymentSpec.allToxiproxyConfigsAndSecretNameToPortMap(
    databaseSchemaProvisioner: DatabaseSchemaProvisioner?,
    userDetailsProvider: UserDetailsProvider
): Pair<List<ToxiproxyConfig>, Map<String, Int>> {
    var nextPortNumber = FIRST_PORT_NUMBER
    val appToxiproxyConfig = listOf(ToxiproxyConfig())
    val endpointsFromConfig = this.endpointsFromConfig(nextPortNumber)
    nextPortNumber = endpointsFromConfig.getNextPortNumber(numberIfEmpty = nextPortNumber)
    val serversAndPortsFromConfig = this.serversAndPortsFromConfig(nextPortNumber)
    nextPortNumber = serversAndPortsFromConfig.getNextPortNumber(numberIfEmpty = nextPortNumber)

    val (databases, secretNameToPortMap) = if (databaseSchemaProvisioner != null) {
        this.databasesFromSecrets(nextPortNumber, databaseSchemaProvisioner, userDetailsProvider)
    } else {
        Pair(emptyList(), emptyMap())
    }

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

internal fun AuroraDeploymentSpec.extractEnabledToxiproxyProxies() = getSubKeyValues(ToxiproxyField.proxies).map {
    ToxiproxyProxy(
        proxyName = it,
        enabled = get("${ToxiproxyField.proxies}/$it/enabled"),
        initialEnabledState = get("${ToxiproxyField.proxies}/$it/initialEnabledState"),
        urlVariable = getOrNull("${ToxiproxyField.proxies}/$it/urlVariable"),
        serverVariable = getOrNull("${ToxiproxyField.proxies}/$it/serverVariable"),
        portVariable = getOrNull("${ToxiproxyField.proxies}/$it/portVariable"),
        database = get("${ToxiproxyField.proxies}/$it/database"),
        databaseName = getOrNull("${ToxiproxyField.proxies}/$it/databaseName")
    )
}.filter { it.enabled }

// Return a list of proxynames and corresponding environment variable names
internal fun AuroraDeploymentSpec.extractToxiproxyEndpoints() =
    extractEnabledToxiproxyProxies().mapNotNull { it.toToxiproxyEndpoint() }

internal fun AuroraDeploymentSpec.extractToxiproxyServersAndPorts() =
    extractEnabledToxiproxyProxies().mapNotNull { it.toToxiproxyServerAndPortVars() }

internal fun AuroraDeploymentSpec.extractToxiproxyDatabases() =
    extractEnabledToxiproxyProxies().mapNotNull { it.toToxiproxyDatabase(this["$databaseDefaultsKey/name"]) }

internal fun AuroraDeploymentSpec.extractToxiproxyNamedDatabases() = extractEnabledToxiproxyProxies()
    .filter { it.isNamedDatabaseProxy() }
    .mapNotNull { it.toToxiproxyDatabase() }

private fun List<ToxiproxyConfig>.findPortByProxyName(proxyName: String) =
    find { it.name == proxyName }?.listen?.substringAfter(':')

private fun String.convertToProxyUrl(port: Int): String =
    UrlParser(this)
        .withModifiedHostName("localhost")
        .withModifiedPort(port)
        .makeString()

internal fun AuroraDeploymentSpec.getToxiproxyEndpointEnvVars(): List<String> =
    extractToxiproxyEndpoints().map { it.varName }
