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
            name = spec["databaseDefaults/name"],
            flavor = spec["databaseDefaults/flavor"],
            generate = spec["databaseDefaults/generate"],
            parameters = applicationFiles.findSubKeys("databaseDefaults/parameters").associateWith {
                spec.get<String>("databaseDefaults/parameters/$it")
            },
            roles = applicationFiles.findSubKeys("databaseDefaults/roles").associateWith {
                spec.get<DatabasePermission>("databaseDefaults/roles/$it")
            },
            exposeTo = applicationFiles.findSubKeys("databaseDefaults/exposeTo").associateWith {
                spec.get<String>("databaseDefaults/exposeTo/$it")
            }

        )
        if (spec.isSimplifiedAndEnabled("database")) {
            return listOf(defaultDb)
        }
        return applicationFiles.findSubKeys("database").map { db ->

            val isSimple = spec.fields.containsKey("database/$db")

            if (isSimple) {
                val value: String = spec["database/$db"]
                defaultDb.copy(
                    name = db,
                    id = if (value == "auto" || value.isBlank()) null else value
                )
            } else {

                val parameters = applicationFiles.associateSubKeys<String>("database/$db/parameters", spec)
                val roles = applicationFiles.associateSubKeys<DatabasePermission>("database/$db/roles", spec)
                val exposeTo = applicationFiles.associateSubKeys<String>("database/$db/exposeTo", spec)
                val value: String = spec.getOrNull("database/$db/id") ?: ""

                Database(
                    name = spec.getOrNull("database/$db/name") ?: db,
                    id = if (value == "auto" || value.isBlank()) null else value,
                    flavor = spec.getOrNull("database/$db/flavor") ?: defaultDb.flavor,
                    generate = spec.getOrNull("database/$db/generate") ?: defaultDb.generate,
                    parameters = defaultDb.parameters + parameters,
                    roles = defaultDb.roles + roles,
                    exposeTo = defaultDb.exposeTo + exposeTo
                )
            }
        }
    }

    fun findDbHandlers(): List<AuroraConfigFieldHandler> {

        return applicationFiles.findSubKeys("database").flatMap { db ->
            val expandedDbKeys = applicationFiles.findSubKeys("database/$db")
            if (expandedDbKeys.isEmpty()) {
                listOf(AuroraConfigFieldHandler("database/$db"))
            } else {
                createExpandedDbHandlers(db)
            }
        }
    }

    private fun createExpandedDbHandlers(db: String): List<AuroraConfigFieldHandler> {

        val mainHandlers = listOf(
            AuroraConfigFieldHandler("database/$db/generate"),
            AuroraConfigFieldHandler("database/$db/name"),
            AuroraConfigFieldHandler("database/$db/id"),
            AuroraConfigFieldHandler(
                "database/$db/flavor", validator = { node ->
                    node?.oneOf(DatabaseFlavor.values().map { it.toString() })
                })
        )

        val validKeyRoles = applicationFiles.findSubKeys("database/$db/roles")
        val validDefaultRoles = applicationFiles.findSubKeys("databaseDefaults/roles")
        val validRoles = validDefaultRoles + validKeyRoles

        val databaseRolesHandlers =
            applicationFiles.findSubHandlers("database/$db/roles", validatorFn = { k ->
                { node ->
                    node.oneOf(DatabasePermission.values().map { it.toString() })
                }
            })

        val databaseExposeToHandlers =
            applicationFiles.findSubHandlers("database/$db/exposeTo", validatorFn = { exposeTo ->
                { node -> exposeToValidator(node, validRoles, exposeTo) }
            })

        val parametersHandlers = applicationFiles.findSubHandlers("database/$db/parameters")

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
                "databaseDefaults/flavor",
                defaultValue = DatabaseFlavor.ORACLE_MANAGED,
                validator = { node ->
                    node.oneOf(DatabaseFlavor.values().map { it.toString() })
                }),
            AuroraConfigFieldHandler("databaseDefaults/generate", defaultValue = true),
            AuroraConfigFieldHandler("databaseDefaults/name", defaultValue = "@name@") // må vi ha på en validator her?
        )

        val databaseDefaultRolesHandlers =
            applicationFiles.findSubHandlers("databaseDefaults/roles", validatorFn = { key ->
                { node ->
                    node.oneOf(DatabasePermission.values().map { it.toString() })
                }
            })

        val validRoles = applicationFiles.findSubKeys("databaseDefaults/roles")

        val databaseDefaultExposeToHandlers =
            applicationFiles.findSubHandlers("databaseDefaults/exposeTo", validatorFn = { exposeTo ->
                { node -> exposeToValidator(node, validRoles, exposeTo) }
            })

        val parametersHandlers = applicationFiles.findSubHandlers("databaseDefaults/parameters")

        return listOf<AuroraConfigFieldHandler>() + databaseDefaultHandler + databaseDefaultRolesHandlers + databaseDefaultExposeToHandlers + parametersHandlers
    }
}