package no.skatteetaten.aurora.boober.feature

import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecValidationException
import no.skatteetaten.aurora.boober.utils.UrlParser
import no.skatteetaten.aurora.boober.utils.whenTrue

internal fun AuroraDeploymentSpec.validateToxiproxy(): List<AuroraDeploymentSpecValidationException> =
    listOf(
        invalidCombinationOfToxiproxyFieldsErrors(),
        missingOrInvalidEndpointVariableErrors(),
        missingServerAndPortVariableErrors(),
        missingNamedDbErrors(),
        invalidDbConfigErrors(),
        proxyNameError(),
        duplicateUrlVariableErrors(),
        duplicateServerAndPortVariableErrors(),
        duplicateDatabaseNameErrors()
    ).flatten()

private fun AuroraDeploymentSpec.invalidCombinationOfToxiproxyFieldsErrors() =
    extractEnabledToxiproxyProxies().mapNotNull { it.invalidCombinationError() }

// Validate that the variables given in toxiproxy/proxies/<proxy name>/urlVariable exist
private fun AuroraDeploymentSpec.missingOrInvalidEndpointVariableErrors() =
    getToxiproxyEndpointEnvVars().mapNotNull { varName ->
        val envVar = getOrNull<String>("config/$varName")
        if (envVar == null) {
            "Found Toxiproxy config for endpoint named $varName, but there is no such environment variable."
        } else if (!UrlParser(envVar).isValid()) {
            "The format of the URL \"$envVar\" given by the config variable $varName is not supported."
        } else null
    }.map(::AuroraDeploymentSpecValidationException)

// Validate that the variables given in toxiproxy/proxies/<proxy name>/serverVariable
// and toxiproxy/proxies/<proxy name>/portVariable exist
private fun AuroraDeploymentSpec.missingServerAndPortVariableErrors() =
    extractToxiproxyServersAndPorts().flatMap { serverAndPortsVars ->

        val (proxyName, serverVar, portVar) = serverAndPortsVars
        val serverAndPort = listOf("server" to serverVar, "port" to portVar)

        serverAndPort.mapNotNull { (name, value) ->
            if (value.isNullOrBlank()) {
                return@mapNotNull "The $name variable is missing for the Toxiproxy proxy named $proxyName."
            }
            val isServerOrPortMissingFromConfig = this.getSubKeyValues("config").none { it == value }
            if (isServerOrPortMissingFromConfig) {
                "Found Toxiproxy config for a $name variable named $value, " +
                    "but there is no such environment variable."
            } else null
        }.map(::AuroraDeploymentSpecValidationException)
    }

// Validate that the "database" and "databaseName" properties are used correctly
private fun AuroraDeploymentSpec.invalidDbConfigErrors(): List<AuroraDeploymentSpecValidationException> {
    val errors = mutableListOf<String>()
    if (isSimplifiedAndEnabled("database") && extractToxiproxyNamedDatabases().isNotEmpty()) {
        errors.add(
            "Found named database(s) in the Toxiproxy config, although the database config is simplified. " +
                "Did you mean to use the property \"database\" instead of \"databaseName\"?"
        )
    }
    val numberOfDefaultDbConfigs = extractEnabledToxiproxyProxies().count { it.isDefaultDatabaseProxy() }
    if (numberOfDefaultDbConfigs > 1) {
        errors.add("The \"database\" property may only be used once in the Toxiproxy config.")
    }
    if (numberOfDefaultDbConfigs > 0 && !isSimplifiedConfig("database")) {
        errors.add(
            "It is not possible to set up a Toxiproxy proxy with the \"database\" property when the database " +
                "config is not simplified. Did you mean to use \"databaseName\"?"
        )
    }
    if (numberOfDefaultDbConfigs > 0 && isSimplifiedAndDisabled("database")) {
        errors.add("It is not possible to set up a Toxiproxy proxy for a disabled database.")
    }
    return errors.map(::AuroraDeploymentSpecValidationException)
}

// Validate that for every database in toxiproxy/proxies/<proxy name>/databaseName,
// there is a corresponding database in the spec
private fun AuroraDeploymentSpec.missingNamedDbErrors() =
    if (isSimplifiedAndEnabled("database")) emptyList()
    else extractToxiproxyNamedDatabases()
        .map { it.databaseName }
        .filterNot(getSubKeyValues("database")::contains)
        .map { "Found Toxiproxy config for database named $it, but there is no such database configured." }
        .map(::AuroraDeploymentSpecValidationException)

// Validate that the proxyname used for incoming calls is not duplicated
private fun AuroraDeploymentSpec.proxyNameError() = extractEnabledToxiproxyProxies()
    .any { it.proxyName == MAIN_PROXY_NAME }
    .whenTrue { "The name \"$MAIN_PROXY_NAME\" is reserved for the proxy for incoming calls." }
    ?.let { listOf(AuroraDeploymentSpecValidationException(it)) }
    ?: emptyList()

// Validate that all urlVariable values are unique
private fun AuroraDeploymentSpec.duplicateUrlVariableErrors() = extractToxiproxyEndpoints()
    .groupingBy { it.varName }
    .eachCount()
    .filter { it.value > 1 }
    .map { AuroraDeploymentSpecValidationException("The url variable \"${it.key}\" is referred to by several proxies.") }

// Validate that all serverVariable and portVariable combinations are unique
private fun AuroraDeploymentSpec.duplicateServerAndPortVariableErrors() = extractToxiproxyServersAndPorts()
    .groupingBy { Pair(it.serverVar, it.portVar) }
    .eachCount()
    .filter { it.value > 1 }
    .map { "The server and port variables \"${it.key.first}\" and \"${it.key.second}\" are referred to by several proxies." }
    .map(::AuroraDeploymentSpecValidationException)

// Validate that all databaseName values are unique
private fun AuroraDeploymentSpec.duplicateDatabaseNameErrors() = extractToxiproxyDatabases()
    .groupingBy { it.databaseName }
    .eachCount()
    .filter { it.value > 1 }
    .map { AuroraDeploymentSpecValidationException("The database name \"${it.key}\" is referred to by several proxies.") }
