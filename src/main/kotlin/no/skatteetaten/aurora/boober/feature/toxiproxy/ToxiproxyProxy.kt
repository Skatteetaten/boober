package no.skatteetaten.aurora.boober.feature.toxiproxy

import no.skatteetaten.aurora.boober.feature.createSchemaRequests
import no.skatteetaten.aurora.boober.feature.databaseDefaultsKey
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
import no.skatteetaten.aurora.boober.utils.countSetValues
import no.skatteetaten.aurora.boober.utils.takeIfNotEmpty
import no.skatteetaten.aurora.boober.utils.whenTrue
import java.net.URI

// This class is meant to exactly reflect the information given in the spec, in order to simplify further processing.
// It contains functions for validating the given properties and converting to the correct type of the ToxiproxyProxy class.
internal data class ToxiproxyProxySpec(
    val proxyName: String,
    val enabled: Boolean,
    val initialEnabledState: Boolean,
    val urlVariableKey: String?,
    val serverVariableKey: String?,
    val portVariableKey: String?,
    val database: Boolean,
    val databaseName: String?
) {

    fun toToxiproxyProxyIfEnabled() = enabled.whenTrue {
        when {
            isEndpointProxy() -> toEndpointToxiproxyProxy()
            isServerAndPortProxy() -> toServerAndPortToxiproxyProxy()
            isDatabaseProxy() -> toDatabaseToxiproxyProxy()
            else -> null
        }
    }

    fun validate(ads: AuroraDeploymentSpec): List<AuroraDeploymentSpecValidationException> =
        invalidCombinationError()?.let(::listOf)
            ?: toToxiproxyProxyIfEnabled()?.validate(ads)
            ?: emptyList()

    private fun isEndpointProxy() = hasValidCombination() && !urlVariableKey.isNullOrBlank()

    private fun isServerAndPortProxy() = hasValidCombination() && hasServerOrPortVariableKey()

    private fun isNamedDatabaseProxy() = hasValidCombination() && !databaseName.isNullOrBlank()

    private fun isDefaultDatabaseProxy() = hasValidCombination() && database

    private fun isDatabaseProxy() = isDefaultDatabaseProxy() || isNamedDatabaseProxy()

    private fun toEndpointToxiproxyProxy() = EndpointToxiproxyProxy(urlVariableKey!!, proxyName, initialEnabledState)

    private fun toServerAndPortToxiproxyProxy() =
        ServerAndPortToxiproxyProxy(serverVariableKey, portVariableKey, proxyName, initialEnabledState)

    private fun toDatabaseToxiproxyProxy() = DatabaseToxiproxyProxy(databaseName, proxyName, initialEnabledState)

    private fun hasValidCombination() = numberOfGivenValues() == 1

    private fun invalidCombinationError() = when (numberOfGivenValues()) {
        0 ->
            "Neither of the fields urlVariableKey, serverVariableKey, portVariableKey, database or " +
                "databaseName are set for the Toxiproxy proxy named $proxyName."
        1 -> null
        else -> "The combination of fields specified for the Toxiproxy proxy named $proxyName is not valid."
    }?.let { exceptionMessage ->
        AuroraDeploymentSpecValidationException(
            exceptionMessage +
                " A valid configuration must contain a value for exactly one of the properties urlVariableKey," +
                " database, or databaseName, or both the properties serverVariableKey and portVariableKey."
        )
    }

    private fun numberOfGivenValues() =
        countSetValues(urlVariableKey, hasServerOrPortVariableKey(), database, databaseName)

    private fun hasServerOrPortVariableKey() = countSetValues(serverVariableKey, portVariableKey) > 0
}

internal typealias UpstreamUrlAndSecretName = Pair<String, String?>

// Parent class for all ToxiproxyProxies
internal abstract class ToxiproxyProxy {

    abstract val proxyName: String
    abstract val initialEnabledState: Boolean

    // Generate information that will be stored in the feature context.
    // That is, the Toxiproxy config for the container's config map, the port it will listen to, and, if needed, the secret name.
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

    // Run both validation functions and return a list of exceptions.
    fun validate(ads: AuroraDeploymentSpec) = validateVariables(ads).addIfNotNull(validateProxyName())

    // Generate the upstream URL and, if the target is a database, find the secret name.
    abstract fun upstreamUrlAndSecretName(
        ads: AuroraDeploymentSpec,
        userDetailsProvider: UserDetailsProvider,
        databaseSchemaProvisioner: DatabaseSchemaProvisioner?
    ): UpstreamUrlAndSecretName?

    // Validate that the given variables or database names exist in the spec and that the URLs given in those variables are valid.
    abstract fun validateVariables(ads: AuroraDeploymentSpec): List<AuroraDeploymentSpecValidationException>

    // Return an exception if the proxy name is "app".
    private fun validateProxyName(): AuroraDeploymentSpecValidationException? = (proxyName == MAIN_PROXY_NAME).whenTrue {
        AuroraDeploymentSpecValidationException("The name \"$MAIN_PROXY_NAME\" is reserved for the proxy for incoming calls.")
    }
}

internal class EndpointToxiproxyProxy(
    val urlVariableKey: String,
    override val proxyName: String,
    override val initialEnabledState: Boolean
) : ToxiproxyProxy() {

    override fun upstreamUrlAndSecretName(
        ads: AuroraDeploymentSpec,
        userDetailsProvider: UserDetailsProvider,
        databaseSchemaProvisioner: DatabaseSchemaProvisioner?
    ) = ads
        .getOrNull<String>("config/$urlVariableKey")
        ?.let(::URI)
        ?.let { uri ->
            val upstreamPort = if (uri.port == -1) {
                if (uri.scheme == "https") PortNumbers.HTTPS_PORT else PortNumbers.HTTP_PORT
            } else uri.port
            uri.host + ":" + upstreamPort
        }
        ?.let { UpstreamUrlAndSecretName(it, null) }

    override fun validateVariables(ads: AuroraDeploymentSpec): List<AuroraDeploymentSpecValidationException> {

        val envVar = ads.getOrNull<String>("config/$urlVariableKey")

        val message = if (envVar == null) {
            "Found Toxiproxy config for endpoint named $urlVariableKey, but there is no such environment variable."
        } else if (!UrlParser(envVar).isValid()) {
            "The format of the URL \"$envVar\" given by the config variable $urlVariableKey is not supported."
        } else return emptyList()

        return listOf(AuroraDeploymentSpecValidationException(message))
    }
}

internal class ServerAndPortToxiproxyProxy(
    val serverVariableKey: String?,
    val portVariableKey: String?,
    override val proxyName: String,
    override val initialEnabledState: Boolean
) : ToxiproxyProxy() {

    override fun upstreamUrlAndSecretName(
        ads: AuroraDeploymentSpec,
        userDetailsProvider: UserDetailsProvider,
        databaseSchemaProvisioner: DatabaseSchemaProvisioner?
    ): UpstreamUrlAndSecretName? {

        val upstreamServer: String? = ads.getOrNull("config/$serverVariableKey")
        val upstreamPort: String? = ads.getOrNull("config/$portVariableKey")

        return if (upstreamServer != null && upstreamPort != null)
            UpstreamUrlAndSecretName("$upstreamServer:$upstreamPort", null)
        else null
    }

    override fun validateVariables(ads: AuroraDeploymentSpec) =
        listOf("server" to serverVariableKey, "port" to portVariableKey)
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
    val databaseName: String?, // Null signifies that the database config in the AuroraDeploymentSpec is simplified
    override val proxyName: String,
    override val initialEnabledState: Boolean
) : ToxiproxyProxy() {

    fun isDefault() = databaseName == null

    override fun upstreamUrlAndSecretName(
        ads: AuroraDeploymentSpec,
        userDetailsProvider: UserDetailsProvider,
        databaseSchemaProvisioner: DatabaseSchemaProvisioner?
    ): UpstreamUrlAndSecretName? {

        if (databaseSchemaProvisioner == null) return null

        val givenOrDefaultName = databaseName ?: ads["$databaseDefaultsKey/name"]

        val request = findDatabases(ads)
            .filter { it.name == givenOrDefaultName }
            .createSchemaRequests(userDetailsProvider, ads)
            .takeIfNotEmpty()
            ?.first()
            ?: return null

        val schema = databaseSchemaProvisioner.findSchema(request) ?: return null

        val upstreamUrl = schema.databaseInstance.host + ":" + schema.databaseInstance.port
        val secretName = request.getSecretName(prefix = ads.name)
        return UpstreamUrlAndSecretName(upstreamUrl, secretName)
    }

    override fun validateVariables(ads: AuroraDeploymentSpec) = if (
        databaseName == null ||
        ads.isSimplifiedAndEnabled("database") ||
        ads.getSubKeyValues("database").contains(databaseName)
    ) emptyList() else {
        listOf(
            AuroraDeploymentSpecValidationException(
                "Found Toxiproxy config for database named $databaseName, but there is no such database configured."
            )
        )
    }
}
