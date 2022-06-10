package no.skatteetaten.aurora.boober.feature.toxiproxy

import no.skatteetaten.aurora.boober.feature.createSchemaRequests
import no.skatteetaten.aurora.boober.feature.findDatabases
import no.skatteetaten.aurora.boober.feature.getSecretName
import no.skatteetaten.aurora.boober.feature.name
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.PortNumbers
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecValidationException
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseSchemaProvisioner
import no.skatteetaten.aurora.boober.utils.UrlParser
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.takeIfNotEmpty
import no.skatteetaten.aurora.boober.utils.whenTrue
import java.net.URI

internal data class ToxiproxyProxySpec(
    val proxyName: String,
    val enabled: Boolean,
    val initialEnabledState: Boolean,
    val urlVariable: String?,
    val serverVariable: String?,
    val portVariable: String?,
    val database: Boolean,
    val databaseName: String?
) {

    private fun isEndpointProxy() = urlVariable != null &&
        urlVariable.isNotEmpty() &&
        listOf(serverVariable, portVariable, databaseName).all { it.isNullOrBlank() } &&
        !database

    private fun isServerAndPortProxy() = listOf(serverVariable, portVariable).any { it != null && it.isNotBlank() } &&
        listOf(urlVariable, databaseName).all { it.isNullOrBlank() } &&
        !database

    private fun isNamedDatabaseProxy() = databaseName != null &&
        databaseName.isNotEmpty() &&
        listOf(urlVariable, serverVariable, portVariable).all { it.isNullOrBlank() } &&
        !database

    private fun isDefaultDatabaseProxy() = database &&
        listOf(urlVariable, serverVariable, portVariable, databaseName).all { it.isNullOrBlank() }

    private fun isDatabaseProxy() = isDefaultDatabaseProxy() || isNamedDatabaseProxy()

    private fun toEndpointToxiproxyProxy() =
        isEndpointProxy().whenTrue { EndpointToxiproxyProxy(urlVariable!!, proxyName, initialEnabledState) }

    private fun toServerAndPortToxiproxyProxy() =
        if (isServerAndPortProxy())
            ServerAndPortToxiproxyProxy(
                serverVariable,
                portVariable,
                proxyName,
                initialEnabledState
            )
        else null

    private fun toDatabaseToxiproxyProxy(defaultName: String?) =
        if (isNamedDatabaseProxy()) DatabaseToxiproxyProxy(databaseName!!, proxyName, initialEnabledState, false)
        else if (defaultName != null && defaultName.isNotBlank() && isDefaultDatabaseProxy())
            DatabaseToxiproxyProxy(defaultName, proxyName, initialEnabledState, true)
        else null

    fun toToxiproxyProxy(defaultDbName: String?) = when {
        isEndpointProxy() -> toEndpointToxiproxyProxy()
        isServerAndPortProxy() -> toServerAndPortToxiproxyProxy()
        isDatabaseProxy() -> toDatabaseToxiproxyProxy(defaultDbName)
        else -> null
    }

    fun invalidCombinationError() = when {
        hasNoReference() ->
            "Neither of the fields urlVariable, serverVariable, portVariable, database or " +
                "databaseName are set for the Toxiproxy proxy named $proxyName."
        isNotValidProxy() ->
            "The combination of fields specified for the Toxiproxy proxy named $proxyName is not valid."
        else -> null
    }?.let { exceptionMessage ->
        AuroraDeploymentSpecValidationException(
            exceptionMessage +
                " A valid configuration must contain a value for exactly one of the properties urlVariable," +
                " database, or databaseName, or both the properties serverVariable and portVariable."
        )
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

internal typealias UpstreamUrlAndSecretName = Pair<String, String?>

internal abstract class ToxiproxyProxy {

    abstract val proxyName: String
    abstract val initialEnabledState: Boolean

    abstract fun upstreamUrlAndSecretName(
        ads: AuroraDeploymentSpec,
        userDetailsProvider: UserDetailsProvider,
        databaseSchemaProvisioner: DatabaseSchemaProvisioner?
    ): UpstreamUrlAndSecretName?

    abstract fun validateVariables(ads: AuroraDeploymentSpec): List<AuroraDeploymentSpecValidationException>

    fun generateConfig(
        ads: AuroraDeploymentSpec,
        port: Int,
        userDetailsProvider: UserDetailsProvider,
        databaseSchemaProvisioner: DatabaseSchemaProvisioner?
    ) = upstreamUrlAndSecretName(ads, userDetailsProvider, databaseSchemaProvisioner)
        .takeIf { it?.first != null }
        ?.let { (upstreamUrl, secretName) ->
            ToxiproxyConfigAndSecret(
                port = port,
                secretName = secretName,
                toxiproxyConfig = ToxiproxyConfig(
                    name = proxyName,
                    listen = "0.0.0.0:$port",
                    upstream = upstreamUrl,
                    enabled = initialEnabledState
                )
            )
        }

    fun validateProxyName(): AuroraDeploymentSpecValidationException? = (proxyName == MAIN_PROXY_NAME).whenTrue {
        AuroraDeploymentSpecValidationException("The name \"$MAIN_PROXY_NAME\" is reserved for the proxy for incoming calls.")
    }

    fun validate(ads: AuroraDeploymentSpec) = validateVariables(ads).addIfNotNull(validateProxyName())
}

internal class EndpointToxiproxyProxy(
    val urlVariable: String,
    override val proxyName: String,
    override val initialEnabledState: Boolean
) : ToxiproxyProxy() {

    override fun upstreamUrlAndSecretName(
        ads: AuroraDeploymentSpec,
        userDetailsProvider: UserDetailsProvider,
        databaseSchemaProvisioner: DatabaseSchemaProvisioner?
    ) = ads
        .getOrNull<String>("config/$urlVariable")
        ?.let(::URI)
        ?.let { uri ->
            val upstreamPort = if (uri.port == -1) {
                if (uri.scheme == "https") PortNumbers.HTTPS_PORT else PortNumbers.HTTP_PORT
            } else uri.port
            uri.host + ":" + upstreamPort
        }
        ?.let { UpstreamUrlAndSecretName(it, null) }

    override fun validateVariables(ads: AuroraDeploymentSpec): List<AuroraDeploymentSpecValidationException> {

        val envVar = ads.getOrNull<String>("config/$urlVariable")

        val message = if (envVar == null) {
            "Found Toxiproxy config for endpoint named $urlVariable, but there is no such environment variable."
        } else if (!UrlParser(envVar).isValid()) {
            "The format of the URL \"$envVar\" given by the config variable $urlVariable is not supported."
        } else return emptyList()

        return listOf(AuroraDeploymentSpecValidationException(message))
    }
}

internal class ServerAndPortToxiproxyProxy(
    val serverVariable: String?,
    val portVariable: String?,
    override val proxyName: String,
    override val initialEnabledState: Boolean
) : ToxiproxyProxy() {

    override fun upstreamUrlAndSecretName(
        ads: AuroraDeploymentSpec,
        userDetailsProvider: UserDetailsProvider,
        databaseSchemaProvisioner: DatabaseSchemaProvisioner?
    ): UpstreamUrlAndSecretName? {

        val upstreamServer: String? = ads.getOrNull("config/$serverVariable")
        val upstreamPort: String? = ads.getOrNull("config/$portVariable")

        return if (upstreamServer != null && upstreamPort != null)
            UpstreamUrlAndSecretName("$upstreamServer:$upstreamPort", null)
        else null
    }

    override fun validateVariables(ads: AuroraDeploymentSpec) =
        listOf("server" to serverVariable, "port" to portVariable)
            .mapNotNull { (name, value) ->
                if (value.isNullOrBlank()) {
                    return@mapNotNull "The $name variable is missing for the Toxiproxy proxy named $proxyName."
                }
                val isServerOrPortMissingFromConfig = ads.getSubKeyValues("config").none { it == value }
                if (isServerOrPortMissingFromConfig) {
                    "Found Toxiproxy config for a $name variable named $value, " +
                        "but there is no such environment variable."
                } else null
            }
            .map(::AuroraDeploymentSpecValidationException)
}

internal class DatabaseToxiproxyProxy(
    val databaseName: String,
    override val proxyName: String,
    override val initialEnabledState: Boolean,
    val isDefault: Boolean
) : ToxiproxyProxy() {

    override fun upstreamUrlAndSecretName(
        ads: AuroraDeploymentSpec,
        userDetailsProvider: UserDetailsProvider,
        databaseSchemaProvisioner: DatabaseSchemaProvisioner?
    ): UpstreamUrlAndSecretName? {

        if (databaseSchemaProvisioner == null) return null

        val request = findDatabases(ads)
            .filter { it.name == databaseName }
            .createSchemaRequests(userDetailsProvider, ads)
            .takeIfNotEmpty()
            ?.first()
            ?: return null

        val schema = databaseSchemaProvisioner.findSchema(request) ?: return null

        val upstreamUrl = schema.databaseInstance.host + ":" + schema.databaseInstance.port
        val secretName = request.getSecretName(prefix = ads.name)
        return UpstreamUrlAndSecretName(upstreamUrl, secretName)
    }

    override fun validateVariables(ads: AuroraDeploymentSpec): List<AuroraDeploymentSpecValidationException> {

        if (isDefault || ads.isSimplifiedAndEnabled("database")) return emptyList()

        return if (ads.getSubKeyValues("database").contains(databaseName)) emptyList()
        else listOf(
            AuroraDeploymentSpecValidationException(
                "Found Toxiproxy config for database named $databaseName, but there is no such database configured."
            )
        )
    }
}
