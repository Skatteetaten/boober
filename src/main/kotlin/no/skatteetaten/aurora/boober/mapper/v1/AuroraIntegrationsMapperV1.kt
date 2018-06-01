package no.skatteetaten.aurora.boober.mapper.v1

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.model.AuroraCertificateSpec
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraIntegration
import no.skatteetaten.aurora.boober.model.Database
import no.skatteetaten.aurora.boober.model.Webseal

class AuroraIntegrationsMapperV1(applicationFiles: List<AuroraConfigFile>) {

    val dbHandlers = findDbHandlers(applicationFiles)

    val handlers = dbHandlers + listOf(
        AuroraConfigFieldHandler("database", defaultValue = false),
        AuroraConfigFieldHandler("certificate/commonName"),
        AuroraConfigFieldHandler("certificate/renewBefore", defaultValue = "30d"),
//        AuroraConfigFieldHandler("certificate/ttl", defaultValue = "365d"), TODO: Ikke støttet i skap
        AuroraConfigFieldHandler("certificate", defaultValue = false),
        AuroraConfigFieldHandler("splunkIndex"),
        AuroraConfigFieldHandler("webseal", defaultValue = false),
        AuroraConfigFieldHandler("webseal/host"),
        AuroraConfigFieldHandler("webseal/roles")
    )

    fun integrations(auroraConfigFields: AuroraConfigFields): AuroraIntegration? {
        val name: String = auroraConfigFields.extract("name")
        val groupId: String = auroraConfigFields.extractIfExistsOrNull<String>("groupId") ?: ""

        val certificateCn = if (auroraConfigFields.isSimplifiedConfig("certificate")) {
            val certFlag: Boolean = auroraConfigFields.extract("certificate")
            if (certFlag) "$groupId.$name" else null
        } else {
            auroraConfigFields.extractOrNull("certificate/commonName")
        }

        val certificate = certificateCn?.let {
            val renewAfter = auroraConfigFields.extract<String>("certificate/renewBefore")
            //val ttl= auroraConfigFields.extract<String>("certificate/ttl") TODO:ikke støttet i skap
            val ttl = "365d"
            AuroraCertificateSpec(it, ttl, renewAfter)
        }
        return AuroraIntegration(
            database = findDatabases(auroraConfigFields, name),
            certificate = certificate,
            splunkIndex = auroraConfigFields.extractOrNull("splunkIndex"),
            webseal = findWebseal(auroraConfigFields)
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