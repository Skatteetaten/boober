package no.skatteetaten.aurora.boober.mapper.v1

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraIntegration
import no.skatteetaten.aurora.boober.model.Database

class AuroraIntegrationsMapperV1(applicationFiles: List<AuroraConfigFile>) {

    val dbHandlers = findDbHandlers(applicationFiles)

    val handlers = dbHandlers + listOf(
            AuroraConfigFieldHandler("database", defaultValue = false),
            AuroraConfigFieldHandler("certificate/commonName"),
            AuroraConfigFieldHandler("certificate", defaultValue = false)
    )

    //TODO: ADD splunk, webseal, bigip

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
                certificateCn = certificateCn
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