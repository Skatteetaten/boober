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
import no.skatteetaten.aurora.boober.utils.noneAreSet
import no.skatteetaten.aurora.boober.utils.takeIfNotEmpty
import no.skatteetaten.aurora.boober.utils.whenTrue
import java.net.URI

// This class is meant to exactly reflect the information given in the spec, in order to simplify further processing.
// It contains functions for validating the given properties and converting to the correct type of the ToxiproxyProxy class.
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

    private fun isEndpointProxy() = !urlVariable.isNullOrBlank() &&
        noneAreSet(serverVariable, portVariable, databaseName, database)

    private fun isServerAndPortProxy() = listOf(serverVariable, portVariable).any { !it.isNullOrBlank() } &&
        noneAreSet(urlVariable, databaseName, database)

    private fun isNamedDatabaseProxy() = !databaseName.isNullOrBlank() &&
        noneAreSet(urlVariable, serverVariable, portVariable, database)

    private fun isDefaultDatabaseProxy() = database &&
        noneAreSet(urlVariable, serverVariable, portVariable, databaseName)

    private fun isDatabaseProxy() = isDefaultDatabaseProxy() || isNamedDatabaseProxy()

    private fun toEndpointToxiproxyProxy() =
        isEndpointProxy().whenTrue { EndpointToxiproxyProxy(urlVariable!!, proxyName, initialEnabledState) }

    private fun toServerAndPortToxiproxyProxy() = isServerAndPortProxy().whenTrue {
        ServerAndPortToxiproxyProxy(serverVariable, portVariable, proxyName, initialEnabledState)
    }

    private fun toDatabaseToxiproxyProxy() =
        isDatabaseProxy().whenTrue { DatabaseToxiproxyProxy(databaseName, proxyName, initialEnabledState) }

    private fun invalidCombinationError() = when {
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

    private fun hasNoReference() = noneAreSet(urlVariable, serverVariable, portVariable, databaseName, database)

    private fun isNotValidProxy() = noneAreSet(isEndpointProxy(), isServerAndPortProxy(), isDatabaseProxy())
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
