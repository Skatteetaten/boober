package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.newVolume
import com.fkorotkov.kubernetes.newVolumeMount
import com.fkorotkov.kubernetes.secret
import io.fabric8.kubernetes.api.model.Volume
import io.fabric8.kubernetes.api.model.VolumeMount
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentContext
import no.skatteetaten.aurora.boober.mapper.v1.*
import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.service.AuroraResource
import no.skatteetaten.aurora.boober.service.Feature
import no.skatteetaten.aurora.boober.service.internal.DbhSecretGenerator
import no.skatteetaten.aurora.boober.service.internal.createDbEnv
import no.skatteetaten.aurora.boober.service.internal.createName
import no.skatteetaten.aurora.boober.service.resourceprovisioning.*
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.ensureStartWith
import no.skatteetaten.aurora.boober.utils.oneOf
import org.aspectj.weaver.tools.cache.SimpleCacheFactory.path
import org.springframework.stereotype.Service

@Service
class DatabaseFeature(
        val databaseSchemaProvisioner: DatabaseSchemaProvisioner
) : Feature {
    val databaseDefaultsKey = "databaseDefaults"

    override fun handlers(header: AuroraDeploymentContext): Set<AuroraConfigFieldHandler> {
        val dbHandlers = findDbHandlers(header.applicationFiles)

        val dbDefaultsHandlers = findDbDefaultHandlers(header.applicationFiles)

        return (dbDefaultsHandlers + dbHandlers + listOf(
                AuroraConfigFieldHandler("database", defaultValue = false, canBeSimplifiedConfig = true)
        )).toSet()
    }

    // TODO: Handle errors, probably need to return both resources and errors and propagate
    override fun generate(adc: AuroraDeploymentContext): Set<AuroraResource> {

        //can we just create schemaRequest manually here?
        val databases = findDatabases(adc)

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
        val volumeName = mountName.replace("_", "-").toLowerCase().ensureStartWith(appName)

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


    override fun modify(adc: AuroraDeploymentContext, resources: Set<AuroraResource>) {
        val databases = findDatabases(adc)
        val dbEnv = databases.flatMap { it.createDbEnv("${it.name}_db") }.addIfNotNull(databases.firstOrNull()?.createDbEnv("db")).toMap().toEnvVars()

        val volumeAndMounts = databases.map { it.createDatabaseVolumesAndMounts(adc.name) }

        val volumes = volumeAndMounts.map { it.first }
        val volumeMounts = volumeAndMounts.map { it.second }

        resources.forEach {
            if (it.resource.kind == "DeploymentConfig") {
                val dc: DeploymentConfig = jacksonObjectMapper().convertValue(it.resource)
                dc.spec.template.spec.volumes.plusAssign(volumes)
                dc.spec.template.spec.containers.forEach { container ->
                    container.env.addAll(dbEnv)
                    container.volumeMounts.plusAssign(volumeMounts)
                }
            }
        }
    }

    fun createSchemaRequest(databases: List<Database>, adc: AuroraDeploymentContext): List<SchemaProvisionRequest> {
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

    private fun findDatabases(adc: AuroraDeploymentContext): List<Database> {
        val defaultFlavor: DatabaseFlavor = adc["$databaseDefaultsKey/flavor"]
        val defaultInstance = findInstance(adc, "$databaseDefaultsKey/instance", defaultFlavor.defaultFallback)
                ?: DatabaseInstance(fallback = defaultFlavor.defaultFallback)

        val defaultDb = Database(
                name = adc["$databaseDefaultsKey/name"],
                flavor = defaultFlavor,
                generate = adc["$databaseDefaultsKey/generate"],
                instance = defaultInstance.copy(labels = defaultInstance.labels + mapOf("affiliation" to adc.affiliation)),
                roles = adc.applicationFiles.associateSubKeys("$databaseDefaultsKey/roles", adc),
                exposeTo = adc.applicationFiles.associateSubKeys("$databaseDefaultsKey/exposeTo", adc)

        )
        if (adc.isSimplifiedAndEnabled("database")) {
            return listOf(defaultDb)
        }
        return adc.applicationFiles.findSubKeys("database").map { db ->
            val key = "database/$db"
            val isSimple = adc.fields.containsKey(key)

            if (isSimple) {
                val value: String = adc[key]
                defaultDb.copy(
                        name = db,
                        id = if (value == "auto" || value.isBlank()) null else value
                )
            } else {

                val roles = adc.applicationFiles.associateSubKeys<DatabasePermission>("$key/roles", adc)
                val exposeTo = adc.applicationFiles.associateSubKeys<String>("$key/exposeTo", adc)
                val flavor: DatabaseFlavor = adc.getOrNull("$key/flavor") ?: defaultDb.flavor
                val instance = findInstance(adc, "$key/instance", flavor.defaultFallback)
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
            adc: AuroraDeploymentContext,
            key: String,
            defaultFallback: Boolean
    ): DatabaseInstance? {

        if (!adc.hasSubKeys(key)) {
            return null
        }
        return DatabaseInstance(
                name = adc.getOrNull("$key/name"),
                fallback = adc.getOrNull("$key/fallback") ?: defaultFallback,
                labels = adc.applicationFiles.associateSubKeys("$key/labels", adc)
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