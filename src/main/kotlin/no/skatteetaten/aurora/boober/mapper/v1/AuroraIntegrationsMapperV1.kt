package no.skatteetaten.aurora.boober.mapper.v1

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraIntegration
import no.skatteetaten.aurora.boober.model.Database
import no.skatteetaten.aurora.boober.model.Webseal
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseEngine
import no.skatteetaten.aurora.boober.utils.oneOf
import org.slf4j.Logger
import org.slf4j.LoggerFactory

enum class DatabaseFlavor(val engine: DatabaseEngine, val managed: Boolean) {
    ORACLE_MANAGED(DatabaseEngine.ORACLE, true),
    POSTGRES_MANAGED(DatabaseEngine.POSTGRES, true)
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
    val name: String
) {
    val logger: Logger = LoggerFactory.getLogger(AuroraIntegrationsMapperV1::class.java)

    val databaseDefaultsKey = "databaseDefaults"
    val dbHandlers = findDbHandlers()

    val dbDefautsHandlers = findDbDefaultHandlers()

    val handlers = dbDefautsHandlers + dbHandlers + listOf(
        AuroraConfigFieldHandler("database", defaultValue = false, canBeSimplifiedConfig = true),
        AuroraConfigFieldHandler("certificate/commonName"),
        AuroraConfigFieldHandler("certificate", defaultValue = false, canBeSimplifiedConfig = true),
        AuroraConfigFieldHandler("splunkIndex"),
        AuroraConfigFieldHandler("webseal", defaultValue = false, canBeSimplifiedConfig = true),
        AuroraConfigFieldHandler("webseal/host"),
        AuroraConfigFieldHandler("webseal/roles")
    )

    fun integrations(auroraDeploymentSpec: AuroraDeploymentSpec): AuroraIntegration? {
        val name: String = auroraDeploymentSpec["name"]

        return AuroraIntegration(
            database = findDatabases(auroraDeploymentSpec),
            certificateCn = findCertificate(auroraDeploymentSpec, name),
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

        val defaultDb = Database(
            name = spec["$databaseDefaultsKey/name"],
            flavor = spec["$databaseDefaultsKey/flavor"],
            generate = spec["$databaseDefaultsKey/generate"],
            parameters = applicationFiles.associateSubKeys("$databaseDefaultsKey/parameters", spec),
            roles = applicationFiles.associateSubKeys("$databaseDefaultsKey/roles", spec),
            exposeTo = applicationFiles.associateSubKeys("$databaseDefaultsKey/exposeTo", spec)

        )
        if (spec.isSimplifiedAndEnabled("database")) {
            return listOf(defaultDb)
        }
        return applicationFiles.findSubKeys("database").map { db ->
            val key = "database/$db"
            val isSimple = spec.fields.containsKey(key)

            if (isSimple) {
                val value: String = spec[key]
                defaultDb.copy(
                    name = db,
                    id = if (value == "auto" || value.isBlank()) null else value
                )
            } else {

                val parameters = applicationFiles.associateSubKeys<String>("$key/parameters", spec)
                val roles = applicationFiles.associateSubKeys<DatabasePermission>("$key/roles", spec)
                val exposeTo = applicationFiles.associateSubKeys<String>("$key/exposeTo", spec)
                val value: String = spec.getOrNull("$key/id") ?: ""

                Database(
                    name = spec.getOrNull("$key/name") ?: db,
                    id = if (value == "auto" || value.isBlank()) null else value,
                    flavor = spec.getOrNull("$key/flavor") ?: defaultDb.flavor,
                    generate = spec.getOrNull("$key/generate") ?: defaultDb.generate,
                    parameters = defaultDb.parameters + parameters,
                    roles = defaultDb.roles + roles,
                    exposeTo = defaultDb.exposeTo + exposeTo
                )
            }
        }
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

        val databaseRolesHandlers =
            applicationFiles.findSubHandlers("$db/roles", validatorFn = { k ->
                { node ->
                    node.oneOf(DatabasePermission.values().map { it.toString() })
                }
            })

        val databaseExposeToHandlers =
            applicationFiles.findSubHandlers("$db/exposeTo", validatorFn = { exposeTo ->
                { node -> exposeToValidator(node, validRoles, exposeTo) }
            })

        val parametersHandlers = applicationFiles.findSubHandlers("$db/parameters")

        return mainHandlers + databaseRolesHandlers + databaseExposeToHandlers + parametersHandlers
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

        val databaseDefaultRolesHandlers =
            applicationFiles.findSubHandlers("$databaseDefaultsKey/roles", validatorFn = { key ->
                { node ->
                    node.oneOf(DatabasePermission.values().map { it.toString() })
                }
            })

        val validRoles = applicationFiles.findSubKeys("$databaseDefaultsKey/roles")

        val databaseDefaultExposeToHandlers =
            applicationFiles.findSubHandlers("$databaseDefaultsKey/exposeTo", validatorFn = { exposeTo ->
                { node -> exposeToValidator(node, validRoles, exposeTo) }
            })

        val parametersHandlers = applicationFiles.findSubHandlers("$databaseDefaultsKey/parameters")

        return listOf<AuroraConfigFieldHandler>() + databaseDefaultHandler + databaseDefaultRolesHandlers + databaseDefaultExposeToHandlers + parametersHandlers
    }
}