package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.newVolume
import com.fkorotkov.kubernetes.newVolumeMount
import com.fkorotkov.kubernetes.secret
import io.fabric8.kubernetes.api.model.Volume
import io.fabric8.kubernetes.api.model.VolumeMount
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentCommand
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.mapper.associateSubKeys
import no.skatteetaten.aurora.boober.mapper.findSubHandlers
import no.skatteetaten.aurora.boober.mapper.findSubKeys
import no.skatteetaten.aurora.boober.mapper.findSubKeysExpanded
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeployment
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecValidationException
import no.skatteetaten.aurora.boober.service.AuroraResource
import no.skatteetaten.aurora.boober.service.Feature
import no.skatteetaten.aurora.boober.service.addVolumesAndMounts
import no.skatteetaten.aurora.boober.service.internal.DbhSecretGenerator
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseEngine
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseSchemaProvisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaForAppRequest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaIdRequest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaProvisionRequest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaRequestDetails
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaUser
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.ensureStartWith
import no.skatteetaten.aurora.boober.utils.oneOf
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class DatabaseFeature(
        val databaseSchemaProvisioner: DatabaseSchemaProvisioner,
        @Value("\${openshift.cluster}") val cluster: String
) : Feature {
    val databaseDefaultsKey = "databaseDefaults"

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraDeploymentCommand): Set<AuroraConfigFieldHandler> {
        val dbHandlers = findDbHandlers(cmd.applicationFiles)

        val dbDefaultsHandlers = findDbDefaultHandlers(cmd.applicationFiles)

        return (dbDefaultsHandlers + dbHandlers + listOf(
                AuroraConfigFieldHandler("database", defaultValue = false, canBeSimplifiedConfig = true)
        )).toSet()
    }

    override fun validate(adc: AuroraDeploymentSpec, fullValidation: Boolean, cmd: AuroraDeploymentCommand): List<Exception> {
        val databases = findDatabases(adc, cmd)
        if (!fullValidation || adc.cluster != cluster || databases.isEmpty()) {
            return emptyList()
        }

        //TODO: here we should probably validate if generate is false aswell?
        return databases.filter { it.id != null }
                .map { SchemaIdRequest(it.id!!, it.createSchemaDetails(adc.affiliation)) }
                .mapNotNull {
                    try {
                        databaseSchemaProvisioner.findSchemaById(it.id, it.details)
                        null
                    } catch (e: Exception) {
                        AuroraDeploymentSpecValidationException("Database schema with id=${it.id} and affiliation=${it.details.affiliation} does not exist")
                    }
                }
    }

    // TODO: Handle errors, probably need to return both resources and errors and propagate
    override fun generate(adc: AuroraDeploymentSpec, cmd: AuroraDeploymentCommand): Set<AuroraResource> {

        //can we just create schemaRequest manually here?
        val databases = findDatabases(adc, cmd)

        if (databases.isEmpty()) return emptySet()

        val schemaRequests = createSchemaRequest(databases, adc)
        val schemaProvisionResult = databaseSchemaProvisioner.provisionSchemas(schemaRequests)

        return schemaProvisionResult.results.map {
            val secretName = "${it.request.details.schemaName}-db".replace("_", "-").toLowerCase().ensureStartWith(adc.name, "-")
            DbhSecretGenerator.createDbhSecret(it, secretName, adc.namespace)
        }.map {
            AuroraResource("${it.metadata.name}-secret", it)
        }.toSet()
    }


    fun Database.createDatabaseVolumesAndMounts(appName: String): Pair<Volume, VolumeMount> {
        val mountName = "${this.name}-db".toLowerCase()
        val volumeName = mountName.replace("_", "-").toLowerCase().ensureStartWith(appName, "-")

        val mount = newVolumeMount {
            name = mountName
            mountPath = "/u01/secrets/app/$mountName"
        }

        val volume =
                newVolume {
                    name = volumeName
                    secret {
                        secretName = volumeName
                    }
                }
        return volume to mount
    }


    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, cmd: AuroraDeploymentCommand) {
        val databases = findDatabases(adc, cmd)
        if (databases.isEmpty()) return
        val dbEnv = databases.flatMap { it.createDbEnv("${it.name}_db") }.addIfNotNull(databases.firstOrNull()?.createDbEnv("db")).toMap().toEnvVars()

        val volumeAndMounts = databases.map { it.createDatabaseVolumesAndMounts(adc.name) }

        val volumes = volumeAndMounts.map { it.first }
        val volumeMounts = volumeAndMounts.map { it.second }

        val databaseId = resources.filter { it.resource.kind == "Secret" }.mapNotNull { it.resource.metadata.labels["dbhId"] }

        resources.forEach {
            if (it.resource.kind == "ApplicationDeployment") {
                val ad: ApplicationDeployment = jacksonObjectMapper().convertValue(it.resource)
                ad.spec.databases = databaseId
            }
        }

        resources.addVolumesAndMounts(dbEnv, volumes, volumeMounts)
    }

    fun createSchemaRequest(databases: List<Database>, adc: AuroraDeploymentSpec): List<SchemaProvisionRequest> {
        return databases.map {

            val details = it.createSchemaDetails(adc.affiliation)
            if (it.id != null) {
                SchemaIdRequest(
                        id = it.id,
                        details = details
                )
            } else {
                SchemaForAppRequest(
                        environment = adc.envName,
                        application = adc.name,
                        details = details,
                        generate = it.generate
                )
            }
        }
    }

    private fun findDatabases(adc: AuroraDeploymentSpec, cmd: AuroraDeploymentCommand): List<Database> {
        val defaultFlavor: DatabaseFlavor = adc["$databaseDefaultsKey/flavor"]
        val defaultInstance = findInstance(adc, cmd, "$databaseDefaultsKey/instance", defaultFlavor.defaultFallback)
                ?: DatabaseInstance(fallback = defaultFlavor.defaultFallback)

        val defaultDb = Database(
                name = adc["$databaseDefaultsKey/name"],
                flavor = defaultFlavor,
                generate = adc["$databaseDefaultsKey/generate"],
                instance = defaultInstance.copy(labels = defaultInstance.labels + mapOf("affiliation" to adc.affiliation)),
                roles = cmd.applicationFiles.associateSubKeys("$databaseDefaultsKey/roles", adc),
                exposeTo = cmd.applicationFiles.associateSubKeys("$databaseDefaultsKey/exposeTo", adc)

        )
        if (adc.isSimplifiedAndEnabled("database")) {
            return listOf(defaultDb)
        }
        return cmd.applicationFiles.findSubKeys("database").map { db ->
            val key = "database/$db"
            val isSimple = adc.fields.containsKey(key)

            if (isSimple) {
                val value: String = adc[key]
                defaultDb.copy(
                        name = db,
                        id = if (value == "auto" || value.isBlank()) null else value
                )
            } else {

                val roles = cmd.applicationFiles.associateSubKeys<DatabasePermission>("$key/roles", adc)
                val exposeTo = cmd.applicationFiles.associateSubKeys<String>("$key/exposeTo", adc)
                val flavor: DatabaseFlavor = adc.getOrNull("$key/flavor") ?: defaultDb.flavor
                val instance = findInstance(adc, cmd, "$key/instance", flavor.defaultFallback)
                val value: String = adc.getOrNull("$key/id") ?: ""

                val instanceName = instance?.name ?: defaultDb.instance.name
                val instanceFallback = instance?.fallback ?: defaultDb.instance.fallback
                val instanceLabels = emptyMap<String, String>().addIfNotNull(defaultDb.instance.labels)
                        .addIfNotNull(instance?.labels)

                Database(
                        name = adc.getOrNull("$key/name") ?: db,
                        id = if (value == "auto" || value.isBlank()) null else value,
                        flavor = flavor,
                        generate = adc.getOrNull("$key/generate") ?: defaultDb.generate,
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
    }

    private fun findInstance(
            adc: AuroraDeploymentSpec,
            cmd: AuroraDeploymentCommand,
            key: String,
            defaultFallback: Boolean
    ): DatabaseInstance? {

        if (!adc.hasSubKeys(key)) {
            return null
        }
        return DatabaseInstance(
                name = adc.getOrNull("$key/name"),
                fallback = adc.getOrNull("$key/fallback") ?: defaultFallback,
                labels = cmd.applicationFiles.associateSubKeys("$key/labels", adc)
        )
    }

    fun findDbHandlers(applicationFiles: List<AuroraConfigFile>): List<AuroraConfigFieldHandler> {

        return applicationFiles.findSubKeysExpanded("database").flatMap { db ->
            val expandedDbKeys = applicationFiles.findSubKeys(db)
            if (expandedDbKeys.isEmpty()) {
                listOf(AuroraConfigFieldHandler(db))
            } else {
                createExpandedDbHandlers(db, applicationFiles)
            }
        }
    }

    private fun createExpandedDbHandlers(db: String, applicationFiles: List<AuroraConfigFile>): List<AuroraConfigFieldHandler> {

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
        val databaseRolesHandlers = findRolesHandlers(db, applicationFiles)

        val databaseExposeToHandlers = findExposeToHandlers(db, validRoles, applicationFiles)
        val instanceHandlers = findInstanceHandlers(db, applicationFiles)

        return mainHandlers + databaseRolesHandlers + databaseExposeToHandlers + instanceHandlers
    }

    fun findDbDefaultHandlers(applicationFiles: List<AuroraConfigFile>): List<AuroraConfigFieldHandler> {

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

        val databaseRolesHandlers = findRolesHandlers(databaseDefaultsKey, applicationFiles)
        val validRoles = applicationFiles.findSubKeys("$databaseDefaultsKey/roles")
        val databaseDefaultExposeToHandlers = findExposeToHandlers(databaseDefaultsKey, validRoles, applicationFiles)
        val instanceHandlers = findInstanceHandlers(databaseDefaultsKey, applicationFiles)

        return listOf<AuroraConfigFieldHandler>() + databaseDefaultHandler + databaseRolesHandlers + databaseDefaultExposeToHandlers + instanceHandlers
    }

    private fun findRolesHandlers(key: String, applicationFiles: List<AuroraConfigFile>): List<AuroraConfigFieldHandler> {
        return applicationFiles.findSubHandlers("$key/roles", validatorFn = { k ->
            { node ->
                node.oneOf(DatabasePermission.values().map { it.toString() })
            }
        })
    }

    private fun findInstanceHandlers(key: String, applicationFiles: List<AuroraConfigFile>): List<AuroraConfigFieldHandler> {
        return applicationFiles.findSubKeys("$key/instance").flatMap {
            if (it == "labels") {
                applicationFiles.findSubHandlers("$key/instance/$it")
            } else {
                listOf(AuroraConfigFieldHandler("$key/instance/$it"))
            }
        }
    }

    private fun findExposeToHandlers(key: String, validRoles: Set<String>, applicationFiles: List<AuroraConfigFile>): List<AuroraConfigFieldHandler> {
        return applicationFiles.findSubHandlers("$key/exposeTo", validatorFn = { exposeTo ->
            { node -> exposeToValidator(node, validRoles, exposeTo) }
        })
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
}


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

data class Database(
        val name: String,
        val id: String? = null,
        val flavor: DatabaseFlavor,
        val generate: Boolean,
        val exposeTo: Map<String, String> = emptyMap(),
        val roles: Map<String, DatabasePermission> = emptyMap(),
        val instance: DatabaseInstance
) {
    val spec: String
        get(): String = (id?.let { "$name:$id" } ?: name).toLowerCase()
}

data class DatabaseInstance(
        val name: String? = null,
        val fallback: Boolean = false,
        val labels: Map<String, String> = emptyMap()
)


fun Database.createSchemaDetails(affiliation: String): SchemaRequestDetails {

    val users = if (this.roles.isEmpty()) {
        listOf(SchemaUser(name = "SCHEMA", role = "a", affiliation = affiliation))
    } else this.roles.map { role ->
        val exportedRole = this.exposeTo.filter { it.value == role.key }.map { it.key }.firstOrNull()
        val userAffiliation = exportedRole ?: affiliation
        SchemaUser(name = role.key, role = role.value.permissionString, affiliation = userAffiliation)
    }

    return SchemaRequestDetails(
            schemaName = this.name.toLowerCase(),
            databaseInstance = this.instance,
            affiliation = affiliation,
            users = users,
            engine = this.flavor.engine
    )
}

fun Database.createDbEnv(envName: String): List<Pair<String, String>> {
    val path = "/u01/secrets/app/${this.name.toLowerCase()}-db"
    val envName = envName.replace("-", "_").toUpperCase()

    return listOf(
            envName to "$path/info",
            "${envName}_PROPERTIES" to "$path/db.properties"
    )
}

/*
fun SchemaProvisionResults.createDatabaseMounts(
        deploymentSpecInternal: AuroraDeploymentSpecInternal
): List<Mount> {
    return results.map {
        val mountPath = "${it.request.details.schemaName}-db".toLowerCase()
        Mount(
                path = "/u01/secrets/app/$mountPath",
                type = MountType.Secret,
                mountName = mountPath,
                volumeName = it.createName(deploymentSpecInternal.name),
                exist = true,
                content = null
        )
    }
}*/