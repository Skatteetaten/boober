package no.skatteetaten.aurora.boober.feature.toxiproxy

import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecValidationException

internal fun AuroraDeploymentSpec.validateToxiproxy(): List<AuroraDeploymentSpecValidationException> =
    individualToxiproxyProxyErrors() +
        extractEnabledToxiproxyProxies().let { it.invalidDbConfigErrors(this) + it.duplicationErrors() }

// Run each proxy's individual validation function
private fun AuroraDeploymentSpec.individualToxiproxyProxyErrors() =
    extractAllToxiproxyProxySpecs().flatMap { it.validate(this) }

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
    val numberOfDefaultDbConfigs = databaseProxies().count { it.isDefault() }
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

// Validate that the variable to proxy mapping is bijective
private fun ToxiproxyProxies.duplicationErrors() = listOf(
    endpointProxies().duplicationErrors("url variable") { it.urlVariable },
    serverAndPortProxies().duplicationErrors("server variable") { it.serverVariable },
    serverAndPortProxies().duplicationErrors("port variable") { it.portVariable },
    databaseProxies().duplicationErrors("database name") { it.databaseName }
).flatten()

private fun <T : ToxiproxyProxy> List<T>.duplicationErrors(
    nameOfThingThatShouldBeUnique: String,
    thingThatShouldBeUnique: (T) -> String?
): List<AuroraDeploymentSpecValidationException> =
    groupingBy(thingThatShouldBeUnique)
        .eachCount()
        .filter { it.key != null && it.value > 1 }
        .map { "The $nameOfThingThatShouldBeUnique \"${it.key}\" is referred to by several proxies." }
        .map(::AuroraDeploymentSpecValidationException)
