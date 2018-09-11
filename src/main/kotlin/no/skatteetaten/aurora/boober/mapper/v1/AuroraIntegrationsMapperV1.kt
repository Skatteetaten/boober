package no.skatteetaten.aurora.boober.mapper.v1

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraIntegration
import no.skatteetaten.aurora.boober.model.Database
import no.skatteetaten.aurora.boober.model.Webseal
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class AuroraIntegrationsMapperV1(applicationFiles: List<AuroraConfigFile>, val skapHost: String?) {
    val logger: Logger = LoggerFactory.getLogger(AuroraIntegrationsMapperV1::class.java)

    val dbHandlers = findDbHandlers(applicationFiles)

    val skapHandlers = skapHost?.let {
        listOf(
            AuroraConfigFieldHandler("certificate", defaultValue = false, canBeSimplifiedConfig = true),
            AuroraConfigFieldHandler("certificate/commonName"),
            AuroraConfigFieldHandler("webseal", defaultValue = false, canBeSimplifiedConfig = true),
            AuroraConfigFieldHandler("webseal/host"),
            AuroraConfigFieldHandler("webseal/roles")
        )
    } ?: listOf()
    val handlers = dbHandlers + listOf(
        AuroraConfigFieldHandler("database", defaultValue = false, canBeSimplifiedConfig = true),
        AuroraConfigFieldHandler("splunkIndex"),
    ) + skapHandlers

    fun integrations(auroraDeploymentSpec: AuroraDeploymentSpec): AuroraIntegration? {
        val name: String = auroraDeploymentSpec["name"]

        return AuroraIntegration(
            database = findDatabases(auroraDeploymentSpec, name),
            certificate = skapHost?.let {findCertificate(auroraDeploymentSpec, name)},
            splunkIndex = auroraDeploymentSpec.getOrNull("splunkIndex"),
            webseal = findWebseal(auroraDeploymentSpec)
        )
    }

    fun findCertificate(auroraDeploymentSpec: AuroraDeploymentSpec, name: String): String? {

        val simplified = auroraDeploymentSpec.isSimplifiedConfig("certificate")
        if (!simplified) {
            return auroraDeploymentSpec.getOrNull("certificate/commonName")
        }

        val value: Boolean = auroraDeploymentSpec["certificate"]
        if (!value) {
            return null
        }
        val groupId: String = auroraDeploymentSpec.getOrNull<String>("groupId") ?: ""
        return "$groupId.$name"
    }

    private fun findWebseal(auroraDeploymentSpec: AuroraDeploymentSpec): Webseal? {
        return auroraDeploymentSpec.featureEnabled("webseal") { field ->
            val roles = auroraDeploymentSpec.getDelimitedStringOrArrayAsSet("$field/roles", ",")
                .takeIf { it.isNotEmpty() }
                ?.joinToString(",")
            Webseal(auroraDeploymentSpec.getOrNull("$field/host"), roles)
        }
    }

    private fun findDatabases(auroraDeploymentSpec: AuroraDeploymentSpec, name: String): List<Database> {

        if (auroraDeploymentSpec.isSimplifiedAndEnabled("database")) {
            return listOf(Database(name = name))
        }

        return auroraDeploymentSpec.getDatabases(dbHandlers)
    }

    fun findDbHandlers(applicationFiles: List<AuroraConfigFile>): List<AuroraConfigFieldHandler> {

        return applicationFiles.findSubKeys("database").map { key ->
            AuroraConfigFieldHandler("database/$key")
        }
    }
}