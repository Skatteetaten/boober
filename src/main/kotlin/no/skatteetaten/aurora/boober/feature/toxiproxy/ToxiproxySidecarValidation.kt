package no.skatteetaten.aurora.boober.feature.toxiproxy

import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecValidationException

internal fun ToxiproxyProxies.validate(ads: AuroraDeploymentSpec): List<AuroraDeploymentSpecValidationException> =
    listOf(
        ads.invalidCombinationOfToxiproxyFieldsErrors(),
        individualToxiproxyProxyErrors(ads),
        invalidDbConfigErrors(ads),
        duplicateUrlVariableErrors(),
        duplicateServerAndPortVariableErrors(),
        duplicateDatabaseNameErrors()
    ).flatten()

private fun AuroraDeploymentSpec.invalidCombinationOfToxiproxyFieldsErrors() =
    extractAllToxiproxyProxySpecs().mapNotNull { it.invalidCombinationError() }

private fun ToxiproxyProxies.individualToxiproxyProxyErrors(ads: AuroraDeploymentSpec) = flatMap { it.validate(ads) }

// Validate that the "database" and "databaseName" properties are used correctly
private fun ToxiproxyProxies.invalidDbConfigErrors(
    ads: AuroraDeploymentSpec
): List<AuroraDeploymentSpecValidationException> {
    val errors = mutableListOf<String>()
    if (ads.isSimplifiedAndEnabled("database") && namedDatabaseProxies().isNotEmpty()) {
        errors.add(
            "Found named database(s) in the Toxiproxy config, although the database config is simplified. " +
                "Did you mean to use the property \"database\" instead of \"databaseName\"?"
        )
    }
    val numberOfDefaultDbConfigs = databaseProxies().count { it.isDefault }
    if (numberOfDefaultDbConfigs > 1) {
        errors.add("The \"database\" property may only be used once in the Toxiproxy config.")
    }
    if (numberOfDefaultDbConfigs > 0 && !ads.isSimplifiedConfig("database")) {
        errors.add(
            "It is not possible to set up a Toxiproxy proxy with the \"database\" property when the database " +
                "config is not simplified. Did you mean to use \"databaseName\"?"
        )
    }
    if (numberOfDefaultDbConfigs > 0 && ads.isSimplifiedAndDisabled("database")) {
        errors.add("It is not possible to set up a Toxiproxy proxy for a disabled database.")
    }
    return errors.map(::AuroraDeploymentSpecValidationException)
}

// Validate that all urlVariable values are unique
private fun ToxiproxyProxies.duplicateUrlVariableErrors() = endpointProxies()
    .groupingBy { it.urlVariable }
    .eachCount()
    .filter { it.value > 1 }
    .map { AuroraDeploymentSpecValidationException("The url variable \"${it.key}\" is referred to by several proxies.") }

// Validate that all serverVariable and portVariable combinations are unique
private fun ToxiproxyProxies.duplicateServerAndPortVariableErrors() = serverAndPortProxies()
    .groupingBy { Pair(it.serverVariable, it.portVariable) }
    .eachCount()
    .filter { it.value > 1 }
    .map { "The server and port variables \"${it.key.first}\" and \"${it.key.second}\" are referred to by several proxies." }
    .map(::AuroraDeploymentSpecValidationException)

// Validate that all databaseName values are unique
private fun ToxiproxyProxies.duplicateDatabaseNameErrors() = databaseProxies()
    .groupingBy { it.databaseName }
    .eachCount()
    .filter { it.value > 1 }
    .map { AuroraDeploymentSpecValidationException("The database name \"${it.key}\" is referred to by several proxies.") }
