package no.skatteetaten.aurora.boober.feature.toxiproxy

import io.fabric8.kubernetes.api.model.Container
import no.skatteetaten.aurora.boober.feature.FeatureContext
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.PortNumbers
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseSchemaProvisioner
import no.skatteetaten.aurora.boober.utils.UrlParser
import no.skatteetaten.aurora.boober.utils.asString
import no.skatteetaten.aurora.boober.utils.editEncodedValue
import no.skatteetaten.aurora.boober.utils.prepend
import no.skatteetaten.aurora.boober.utils.setEnvVarValueIfExists
import no.skatteetaten.aurora.boober.utils.toProperties
import no.skatteetaten.aurora.boober.utils.transformEnvVarValueIfExists

private const val FEATURE_NAME = "toxiproxy"

private const val FIRST_PORT_NUMBER = 18000 // The first Toxiproxy port will be set to this number

internal const val MAIN_PROXY_NAME = "app"

internal object ToxiproxyField {
    const val version = "$FEATURE_NAME/version"
    const val proxies = "$FEATURE_NAME/proxies"
}

internal data class ToxiproxyConfig(
    val name: String = MAIN_PROXY_NAME,
    val listen: String = "0.0.0.0:" + PortNumbers.TOXIPROXY_HTTP_PORT,
    val upstream: String = "0.0.0.0:" + PortNumbers.INTERNAL_HTTP_PORT,
    val enabled: Boolean = true
)

internal data class ToxiproxyConfigAndSecret(
    val port: Int = PortNumbers.TOXIPROXY_HTTP_PORT,
    val secretName: String? = null,
    val toxiproxyConfig: ToxiproxyConfig = ToxiproxyConfig()
)

internal val AuroraDeploymentSpec.toxiproxyVersion: String?
    get() = featureEnabled(FEATURE_NAME) { this[ToxiproxyField.version] }

internal typealias ToxiproxyConfigsAndSecrets = List<ToxiproxyConfigAndSecret>

internal fun AuroraDeploymentSpec.allToxiproxyConfigsAndSecrets(
    databaseSchemaProvisioner: DatabaseSchemaProvisioner?,
    userDetailsProvider: UserDetailsProvider
): ToxiproxyConfigsAndSecrets = extractEnabledToxiproxyProxies()
    .mapIndexedNotNull { i, toxiproxyProxy ->
        toxiproxyProxy.generateConfig(
            this,
            FIRST_PORT_NUMBER + i,
            userDetailsProvider,
            databaseSchemaProvisioner
        )
    }
    .prepend(ToxiproxyConfigAndSecret())

internal fun ToxiproxyConfigsAndSecrets.getConfigs(): List<ToxiproxyConfig> = map { it.toxiproxyConfig }

internal fun ToxiproxyConfigsAndSecrets.getPortBySecretName(secretName: String) = find { it.secretName == secretName }?.port

internal fun ToxiproxyConfigsAndSecrets.getPortByProxyName(proxyName: String) =
    find { it.toxiproxyConfig.name == proxyName }?.port

internal fun MutableMap<String, String>.convertEncryptedJdbcUrlToEncryptedProxyUrl(toxiproxyPort: Int) {
    editEncodedValue("db.properties") {
        val props = it.toProperties()
        val jdbcUrl = props["jdbc.url"].toString()
        props.setProperty("jdbc.url", jdbcUrl.convertToProxyUrl(toxiproxyPort))
        props.asString()
    }
    editEncodedValue("jdbcurl") { it.convertToProxyUrl(toxiproxyPort) }
}

internal fun List<Container>.overrideEnvVarsWithProxies(
    adc: AuroraDeploymentSpec,
    context: FeatureContext
) = forEach { container ->

    adc.extractEnabledToxiproxyProxies().forEach { proxy ->

        val port = context.toxiproxyConfigsAndSecrets.getPortByProxyName(proxy.proxyName)

        when (proxy) {
            is EndpointToxiproxyProxy ->
                container.transformEnvVarValueIfExists(proxy.urlVariableKey) { it.convertToProxyUrl(port!!) }
            is ServerAndPortToxiproxyProxy -> {
                container.setEnvVarValueIfExists(proxy.serverVariableKey!!, "localhost")
                container.setEnvVarValueIfExists(proxy.portVariableKey!!, port!!.toString())
            }
        }
    }
}

internal fun AuroraDeploymentSpec.extractAllToxiproxyProxySpecs(): List<ToxiproxyProxySpec> =
    getSubKeyValues(ToxiproxyField.proxies).map {
        ToxiproxyProxySpec(
            proxyName = it,
            enabled = get("${ToxiproxyField.proxies}/$it/enabled"),
            initialEnabledState = get("${ToxiproxyField.proxies}/$it/initialEnabledState"),
            urlVariableKey = getOrNull("${ToxiproxyField.proxies}/$it/urlVariableKey"),
            serverVariableKey = getOrNull("${ToxiproxyField.proxies}/$it/serverVariableKey"),
            portVariableKey = getOrNull("${ToxiproxyField.proxies}/$it/portVariableKey"),
            database = get("${ToxiproxyField.proxies}/$it/database"),
            databaseName = getOrNull("${ToxiproxyField.proxies}/$it/databaseName")
        )
    }

internal typealias ToxiproxyProxies = List<ToxiproxyProxy>

internal fun AuroraDeploymentSpec.extractEnabledToxiproxyProxies(): ToxiproxyProxies =
    extractAllToxiproxyProxySpecs().mapNotNull { it.toToxiproxyProxyIfEnabled() }

internal fun ToxiproxyProxies.endpointProxies() = filterIsInstance<EndpointToxiproxyProxy>()

internal fun ToxiproxyProxies.serverAndPortProxies() = filterIsInstance<ServerAndPortToxiproxyProxy>()

internal fun ToxiproxyProxies.databaseProxies() = filterIsInstance<DatabaseToxiproxyProxy>()

internal fun ToxiproxyProxies.namedDatabaseProxies() = databaseProxies().filterNot { it.isDefault() }

private fun String.convertToProxyUrl(port: Int): String =
    UrlParser(this)
        .withModifiedHostName("localhost")
        .withModifiedPort(port)
        .makeString()
