package no.skatteetaten.aurora.boober.mapper.v1

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraIntegration
import no.skatteetaten.aurora.boober.model.Database
import no.skatteetaten.aurora.boober.model.DatabaseInstance
import no.skatteetaten.aurora.boober.model.Webseal
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseEngine
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.oneOf
import org.slf4j.Logger
import org.slf4j.LoggerFactory

enum class DatabaseFlavor(val engine: DatabaseEngine, val managed: Boolean, val defaultFallback: Boolean) {
    ORACLE_MANAGED(
        engine = DatabaseEngine.ORACLE,
        managed = true,
        defaultFallback = true
    ),
    POSTGRES_MANAGED(
        engine = DatabaseEngine.POSTGRES,
        managed = true,
        defaultFallback = false
    )
}

enum class DatabasePermission(
    val permissionString: String
) {
    READ("r"),
    WRITE("rw"),
    ALL("a")
}

class AuroraIntegrationsMapperV1(
    val applicationFiles: List<AuroraConfigFile>,
    val name: String,
    val affiliation: String
) {
    val logger: Logger = LoggerFactory.getLogger(AuroraIntegrationsMapperV1::class.java)

    val databaseDefaultsKey = "databaseDefaults"
    val dbHandlers = findDbHandlers()

    val dbDefaultsHandlers = findDbDefaultHandlers()

    val handlers = dbDefaultsHandlers + dbHandlers + listOf(
        AuroraConfigFieldHandler("database", defaultValue = false, canBeSimplifiedConfig = true),
        AuroraConfigFieldHandler("splunkIndex"),
        AuroraConfigFieldHandler("certificate", defaultValue = false, canBeSimplifiedConfig = true),
        AuroraConfigFieldHandler("certificate/commonName"),
        AuroraConfigFieldHandler("webseal", defaultValue = false, canBeSimplifiedConfig = true),
        AuroraConfigFieldHandler("webseal/host"),
        AuroraConfigFieldHandler("webseal/roles")
    )

    fun integrations(auroraDeploymentSpec: AuroraDeploymentSpec): AuroraIntegration? {
        val name: String = auroraDeploymentSpec["name"]

        return AuroraIntegration(
            database = findDatabases(auroraDeploymentSpec),
            certificate = findCertificate(auroraDeploymentSpec, name),
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

    private fun findDatabases(spec: AuroraDeploymentSpec): List<Database> {
        val defaultFlavor: DatabaseFlavor = spec["$databaseDefaultsKey/flavor"]
        val defaultInstance = findInstance(spec, "$databaseDefaultsKey/instance", defaultFlavor.defaultFallback)
            ?: DatabaseInstance(fallback = defaultFlavor.defaultFallback)

        val defaultDb = Database(
            name = spec["$databaseDefaultsKey/name"],
            flavor = defaultFlavor,
            generate = spec["$databaseDefaultsKey/generate"],
            instance = defaultInstance.copy(labels = defaultInstance.labels + mapOf("affiliation" to affiliation)),
            roles = applicationFiles.associateSubKeys("$databaseDefaultsKey/roles", spec),
            exposeTo = applicationFiles.associateSubKeys("$databaseDefaultsKey/exposeTo", spec)

        )
        if (spec.isSimplifiedAndEnabled("database")) {
            return listOf(defaultDb)
        }
        return applicationFiles.findSubKeys("database").mapNotNull { db -> findDatabase(db, spec, defaultDb) }
    }

    private fun findDatabase(
        db: String,
        spec: AuroraDeploymentSpec,
        defaultDb: Database
    ): Database? {
        val key = "database/$db"
        val isSimple = spec.fields.containsKey(key)

        return if (isSimple) {
            val value: String = spec[key]
            if (value == "false") {
                return null
            }
            defaultDb.copy(
                name = db,
                id = if (value == "auto" || value.isBlank()) null else value
            )
        } else {

            if (!spec.get<Boolean>("$key/enabled")) {
                return null
            }
            val roles = applicationFiles.associateSubKeys<DatabasePermission>("$key/roles", spec)
            val exposeTo = applicationFiles.associateSubKeys<String>("$key/exposeTo", spec)
            val flavor: DatabaseFlavor = spec.getOrNull("$key/flavor") ?: defaultDb.flavor
            val instance = findInstance(spec, "$key/instance", flavor.defaultFallback)
            val value: String = spec.getOrNull("$key/id") ?: ""

            val instanceName = instance?.name ?: defaultDb.instance.name
            val instanceFallback = instance?.fallback ?: defaultDb.instance.fallback
            val instanceLabels = emptyMap<String, String>().addIfNotNull(defaultDb.instance.labels)
                .addIfNotNull(instance?.labels)

            Database(
                name = spec.getOrNull("$key/name") ?: db,
                id = if (value == "auto" || value.isBlank()) null else value,
                flavor = flavor,
                generate = spec.getOrNull("$key/generate") ?: defaultDb.generate,
                instance = DatabaseInstance(
                    name = instanceName,
                    fallback = instanceFallback,
                    labels = instanceLabels
                ),
                roles = defaultDb.roles + roles,
                exposeTo = defaultDb.exposeTo + exposeTo
            )
        }
    }

    private fun findInstance(
        spec: AuroraDeploymentSpec,
        key: String,
        defaultFallback: Boolean
    ): DatabaseInstance? {

        if (!spec.hasSubKeys(key)) {
            return null
        }
        return DatabaseInstance(
            name = spec.getOrNull("$key/name"),
            fallback = spec.getOrNull("$key/fallback") ?: defaultFallback,
            labels = applicationFiles.associateSubKeys("$key/labels", spec)
        )
    }

    fun findDbHandlers(): List<AuroraConfigFieldHandler> {

        return applicationFiles.findSubKeysExpanded("database").flatMap { db ->
            val expandedDbKeys = applicationFiles.findSubKeys(db)
            if (expandedDbKeys.isEmpty()) {
                listOf(AuroraConfigFieldHandler(db))
            } else {
                createExpandedDbHandlers(db)
            }
        }
    }

    private fun createExpandedDbHandlers(db: String): List<AuroraConfigFieldHandler> {

        val mainHandlers = listOf(
            AuroraConfigFieldHandler("$db/enabled", defaultValue = true),
            AuroraConfigFieldHandler("$db/generate"),
            AuroraConfigFieldHandler("$db/name"),
            AuroraConfigFieldHandler("$db/id"),
            AuroraConfigFieldHandler(
                "$db/flavor", validator = { node ->
                    node?.oneOf(DatabaseFlavor.values().map { it.toString() })
                })
        )

        val validKeyRoles = applicationFiles.findSubKeys("$db/roles")
        val validDefaultRoles = applicationFiles.findSubKeys("$databaseDefaultsKey/roles")
        val validRoles = validDefaultRoles + validKeyRoles
        val databaseRolesHandlers = findRolesHandlers(db)

        val databaseExposeToHandlers = findExposeToHandlers(db, validRoles)
        val instanceHandlers = findInstanceHandlers(db)

        return mainHandlers + databaseRolesHandlers + databaseExposeToHandlers + instanceHandlers
    }

    private fun exposeToValidator(
        node: JsonNode?,
        validRoles: Set<String>,
        exposeTo: String
    ): Exception? {
        val role = node?.textValue() ?: ""
        return if (validRoles.contains(role)) {
            null
        } else {
            val validRolesString = validRoles.joinToString(",")
            IllegalArgumentException(
                "Database cannot expose affiliation=$exposeTo with invalid role=$role. ValidRoles=$validRolesString"
            )
        }
    }

    fun findDbDefaultHandlers(): List<AuroraConfigFieldHandler> {

        val databaseDefaultHandler = listOf(
            AuroraConfigFieldHandler(
                "$databaseDefaultsKey/flavor",
                defaultValue = DatabaseFlavor.ORACLE_MANAGED,
                validator = { node ->
                    node.oneOf(DatabaseFlavor.values().map { it.toString() })
                }),
            AuroraConfigFieldHandler("$databaseDefaultsKey/generate", defaultValue = true),
            AuroraConfigFieldHandler(
                "$databaseDefaultsKey/name",
                defaultValue = "@name@"
            ) // må vi ha på en validator her?
        )

        val databaseRolesHandlers = findRolesHandlers(databaseDefaultsKey)
        val validRoles = applicationFiles.findSubKeys("$databaseDefaultsKey/roles")
        val databaseDefaultExposeToHandlers = findExposeToHandlers(databaseDefaultsKey, validRoles)
        val instanceHandlers = findInstanceHandlers(databaseDefaultsKey)

        return listOf<AuroraConfigFieldHandler>() + databaseDefaultHandler + databaseRolesHandlers + databaseDefaultExposeToHandlers + instanceHandlers
    }

    private fun findRolesHandlers(key: String): List<AuroraConfigFieldHandler> {
        return applicationFiles.findSubHandlers("$key/roles", validatorFn = { k ->
            { node ->
                node.oneOf(DatabasePermission.values().map { it.toString() })
            }
        })
    }

    private fun findInstanceHandlers(key: String): List<AuroraConfigFieldHandler> {
        return applicationFiles.findSubKeys("$key/instance").flatMap {
            if (it == "labels") {
                applicationFiles.findSubHandlers("$key/instance/$it")
            } else {
                listOf(AuroraConfigFieldHandler("$key/instance/$it"))
            }
        }
    }

    private fun findExposeToHandlers(key: String, validRoles: Set<String>): List<AuroraConfigFieldHandler> {
        return applicationFiles.findSubHandlers("$key/exposeTo", validatorFn = { exposeTo ->
            { node -> exposeToValidator(node, validRoles, exposeTo) }
        })
    }
}