package no.skatteetaten.aurora.boober.mapper.v1

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraIntegration
import no.skatteetaten.aurora.boober.model.Database
import no.skatteetaten.aurora.boober.model.Webseal

class AuroraIntegrationsMapperV1(applicationFiles: List<AuroraConfigFile>, val skapHost: String?) {

    val dbHandlers = findDbHandlers(applicationFiles)

    val skapHandlers = skapHost?.let {
        listOf(
            AuroraConfigFieldHandler("certificate", defaultValue = false),
            AuroraConfigFieldHandler("webseal", defaultValue = false),
            AuroraConfigFieldHandler("webseal/host"),
            AuroraConfigFieldHandler("webseal/roles")
        )
    } ?: listOf()

    val handlers = dbHandlers + listOf(
        AuroraConfigFieldHandler("database", defaultValue = false),
        AuroraConfigFieldHandler("certificate/commonName"),
        AuroraConfigFieldHandler("splunkIndex")
    ) + skapHandlers

    fun integrations(auroraConfigFields: AuroraConfigFields): AuroraIntegration? {
        val name: String = auroraConfigFields.extract("name")
        val groupId: String = auroraConfigFields.extractIfExistsOrNull<String>("groupId") ?: ""

        val certificateCn = if (auroraConfigFields.isSimplifiedConfig("certificate")) {
            val certFlag: Boolean = auroraConfigFields.extract("certificate")
            if (certFlag) "$groupId.$name" else null
        } else {
            auroraConfigFields.extractOrNull("certificate/commonName")
        }

        return AuroraIntegration(
            database = findDatabases(auroraConfigFields, name),
            splunkIndex = auroraConfigFields.extractOrNull("splunkIndex"),
            certificate = skapHost?.let {certificateCn},
            webseal = skapHost?.let{ findWebseal(auroraConfigFields) }
        )
    }

    private fun findWebseal(auroraConfigFields: AuroraConfigFields): Webseal? {

        val name = "webseal"
        if (auroraConfigFields.disabledAndNoSubKeys(name)) {
            return null
        }

        val roles = auroraConfigFields.extractDelimitedStringOrArrayAsSet("$name/roles", ",")
            .takeIf { it.isNotEmpty() }
            ?.joinToString(",")
        return Webseal(
            auroraConfigFields.extractOrNull("$name/host"),
            roles
        )
    }

    private fun findDatabases(auroraConfigFields: AuroraConfigFields, name: String): List<Database> {

        val simplified = auroraConfigFields.isSimplifiedConfig("database")

        if (simplified && auroraConfigFields.extract("database")) {
            return listOf(Database(name = name))
        }
        return auroraConfigFields.getDatabases(dbHandlers)
    }

    fun findDbHandlers(applicationFiles: List<AuroraConfigFile>): List<AuroraConfigFieldHandler> {

        val keys = applicationFiles.findSubKeys("database")

        return keys.map { key ->
            AuroraConfigFieldHandler("database/$key")
        }
    }
}