package no.skatteetaten.aurora.boober.mapper.v1

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraIntegration
import no.skatteetaten.aurora.boober.model.Database
import no.skatteetaten.aurora.boober.model.Webseal

class AuroraIntegrationsMapperV1(applicationFiles: List<AuroraConfigFile>) {

    val dbHandlers = findDbHandlers(applicationFiles)

    val handlers = dbHandlers + listOf(
        AuroraConfigFieldHandler("database", defaultValue = false, subKeyFlag = true),
        AuroraConfigFieldHandler("certificate/commonName"),
        AuroraConfigFieldHandler("certificate", defaultValue = false),
        AuroraConfigFieldHandler("splunkIndex"),
        AuroraConfigFieldHandler("webseal", defaultValue = false, subKeyFlag = true),
        AuroraConfigFieldHandler("webseal/host"),
        AuroraConfigFieldHandler("webseal/roles")
    )

    // TODO: ADD splunk, webseal, bigip

    fun integrations(auroraDeploymentSpec: AuroraDeploymentSpec): AuroraIntegration? {
        val name: String = auroraDeploymentSpec["name"]
        val groupId: String = auroraDeploymentSpec.getOrNull<String>("groupId") ?: ""

        val certificateCn = if (auroraDeploymentSpec.isSimplifiedConfig("certificate")) {
            val certFlag: Boolean = auroraDeploymentSpec["certificate"]
            if (certFlag) "$groupId.$name" else null
        } else {
            auroraDeploymentSpec.getOrNull("certificate/commonName")
        }
        return AuroraIntegration(
            database = findDatabases(auroraDeploymentSpec, name),
            certificateCn = certificateCn,
            splunkIndex = auroraDeploymentSpec.getOrNull("splunkIndex"),
            webseal = findWebseal(auroraDeploymentSpec)
        )
    }

    private fun findWebseal(auroraDeploymentSpec: AuroraDeploymentSpec): Webseal? {

        val name = "webseal"
        if (auroraDeploymentSpec.disabledAndNoSubKeys(name)) {
            return null
        }

        val roles = auroraDeploymentSpec.getDelimitedStringOrArrayAsSet("$name/roles", ",")
            .takeIf { it.isNotEmpty() }
            ?.joinToString(",")
        return Webseal(
            auroraDeploymentSpec.getOrNull("$name/host"),
            roles
        )
    }

    private fun findDatabases(auroraDeploymentSpec: AuroraDeploymentSpec, name: String): List<Database> {

        val simplified = auroraDeploymentSpec.isSimplifiedConfig("database")

        if (simplified && auroraDeploymentSpec["database"] && auroraDeploymentSpec.noSpecifiedSubKeys("database")) {
            return listOf(Database(name = name))
        }
        return auroraDeploymentSpec.getDatabases(dbHandlers)
    }

    fun findDbHandlers(applicationFiles: List<AuroraConfigFile>): List<AuroraConfigFieldHandler> {

        val keys = applicationFiles.findSubKeys("database")

        return keys.map { key ->
            AuroraConfigFieldHandler("database/$key")
        }
    }
}