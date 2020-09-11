package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newSecret
import com.fkorotkov.kubernetes.newVolume
import com.fkorotkov.kubernetes.newVolumeMount
import com.fkorotkov.kubernetes.secret
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.api.model.Volume
import io.fabric8.kubernetes.api.model.VolumeMount
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.Paths.secretsPath
import no.skatteetaten.aurora.boober.model.addVolumesAndMounts
import no.skatteetaten.aurora.boober.model.associateSubKeys
import no.skatteetaten.aurora.boober.model.findSubHandlers
import no.skatteetaten.aurora.boober.model.findSubKeys
import no.skatteetaten.aurora.boober.model.findSubKeysExpanded
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeployment
import no.skatteetaten.aurora.boober.service.HerkimerService
import no.skatteetaten.aurora.boober.service.ResourceKind
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseEngine
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseSchemaProvisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DbhSchema
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaForAppRequest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaIdRequest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaProvisionRequest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaProvisionResult
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaRequestDetails
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaUser
import no.skatteetaten.aurora.boober.utils.ConditionalOnPropertyMissingOrEmpty
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.boolean
import no.skatteetaten.aurora.boober.utils.ensureStartWith
import no.skatteetaten.aurora.boober.utils.oneOf
import org.apache.commons.codec.binary.Base64
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.util.Properties

private val logger = KotlinLogging.logger { }

@ConditionalOnPropertyMissingOrEmpty("integrations.dbh.url")
@Service
class DatabaseDisabledFeature(
    @Value("\${openshift.cluster}") cluster: String
) : DatabaseFeatureTemplate(cluster) {

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        cmd: AuroraContextCommand
    ): List<Exception> {
        val databases = findDatabases(adc, cmd)
        if (databases.isNotEmpty()) {
            return listOf(IllegalArgumentException("Databases are not supported in this cluster"))
        }
        return emptyList()
    }
}

@Service
@ConditionalOnProperty("integrations.dbh.url")
class DatabaseFeature(
    val databaseSchemaProvisioner: DatabaseSchemaProvisioner,
    val herkimerService: HerkimerService,
    @Value("\${openshift.cluster}") cluster: String
) : DatabaseFeatureTemplate(cluster) {

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        cmd: AuroraContextCommand
    ): List<Exception> {
        val databases = findDatabases(adc, cmd)
        if (!fullValidation || adc.cluster != cluster || databases.isEmpty()) {
            return emptyList()
        }

        val databasesThatShouldBeThere = databases.filter { it.id != null || !it.generate }
        val requests = createSchemaRequest(databasesThatShouldBeThere, adc)

        return requests.mapNotNull { request ->
            try {
                databaseSchemaProvisioner.provisionSchema(request)
                null
            } catch (e: Exception) {
                e
            }
        }
    }

    override fun generate(adc: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraResource> {

        // can we just create schemaRequest manually here?
        val databases = findDatabases(adc, cmd)

        if (databases.isEmpty()) return emptySet()

        val databaseFlavor: DatabaseFlavor = adc["$databaseDefaultsKey/flavor"]
        val resourceKind = databaseFlavor.toResourceKind()
        val id = herkimerService.createApplicationDeployment(adc.createApplicationDeploymentPayload()).id
        val resourceWithClaims = herkimerService.getClaimedResources(id, resourceKind).firstOrNull()

        val schemaProvisionResult =
            if (resourceWithClaims?.claims != null) jacksonObjectMapper().convertValue(resourceWithClaims.claims.single().credentials)
            else {
                val schemaRequests = createSchemaRequest(databases, adc)
                databaseSchemaProvisioner.provisionSchemas(schemaRequests).results.also {
                    herkimerService.createResourceAndClaim(
                        ownerId = id,
                        resourceKind = resourceKind,
                        resourceName = "${adc.name}/${adc.namespace}-$resourceKind",
                        credentials = it
                    )
                }
            }

        return schemaProvisionResult.map {
            val secretName =
                "${it.request.details.schemaName}-db".replace("_", "-").toLowerCase().ensureStartWith(adc.name, "-")
            DbhSecretGenerator.createDbhSecret(it, secretName, adc.namespace)
        }.map {
            generateResource(it)
        }.toSet()
    }

    fun DatabaseFlavor.toResourceKind(): ResourceKind =
        if (this.managed) {
            when (this.engine) {
                DatabaseEngine.POSTGRES -> ResourceKind.ManagedPostgresDatabase
                DatabaseEngine.ORACLE -> ResourceKind.ManagedOracleSchema
            }
        } else ResourceKind.ExternalSchema

    fun Database.createDatabaseVolumesAndMounts(appName: String): Pair<Volume, VolumeMount> {
        val mountName = "${this.name}-db".toLowerCase()
        val volumeName = mountName.replace("_", "-").toLowerCase().ensureStartWith(appName, "-")

        val mount = newVolumeMount {
            name = volumeName
            mountPath = "$secretsPath/$mountName"
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

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, cmd: AuroraContextCommand) {
        val databases = findDatabases(adc, cmd)
        if (databases.isEmpty()) return

        val firstEnv = databases.firstOrNull()?.let {
            createDbEnv("${it.name}-db", "db")
        }
        val dbEnv = databases.flatMap { createDbEnv("${it.name}-db") }
            .addIfNotNull(firstEnv).toMap().toEnvVars()

        val volumeAndMounts = databases.map { it.createDatabaseVolumesAndMounts(adc.name) }

        val volumes = volumeAndMounts.map { it.first }
        val volumeMounts = volumeAndMounts.map { it.second }

        val databaseId = resources.filter { it.resource.kind == "Secret" }.mapNotNull {
            it.resource.metadata?.labels?.get("dbhId")
        }

        resources.forEach {
            if (it.resource.kind == "ApplicationDeployment") {
                modifyResource(it, "Added databaseId")
                val ad: ApplicationDeployment = it.resource as ApplicationDeployment
                ad.spec.databases = databaseId
            }
        }

        resources.addVolumesAndMounts(dbEnv, volumes, volumeMounts, this::class.java)
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
                    application = it.applicationLabel ?: adc.name,
                    details = details,
                    generate = it.generate
                )
            }
        }
    }
}

abstract class DatabaseFeatureTemplate(val cluster: String) : Feature {

    val databaseDefaultsKey = "databaseDefaults"

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        val dbHandlers = findDbHandlers(cmd.applicationFiles)

        val dbDefaultsHandlers = findDbDefaultHandlers(cmd.applicationFiles)

        return (dbDefaultsHandlers + dbHandlers + listOf(
            AuroraConfigFieldHandler(
                "database",
                validator = { it.boolean() },
                defaultValue = false,
                canBeSimplifiedConfig = true
            )
        )).toSet()
    }

    fun findDatabases(adc: AuroraDeploymentSpec, cmd: AuroraContextCommand): List<Database> {
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
        return cmd.applicationFiles.findSubKeys("database").mapNotNull { db -> findDatabase(db, adc, defaultDb, cmd) }
    }

    private fun findDatabase(
        db: String,
        adc: AuroraDeploymentSpec,
        defaultDb: Database,
        cmd: AuroraContextCommand
    ): Database? {
        val key = "database/$db"
        val isSimple = adc.fields.containsKey(key)

        return if (isSimple) {
            val value: String = adc[key]
            if (value == "false") {
                return null
            }
            defaultDb.copy(
                name = db,
                id = if (value == "auto" || value.isBlank()) null else value
            )
        } else {
            if (!adc.get<Boolean>("$key/enabled")) {
                return null
            }
            val roles = cmd.applicationFiles.associateSubKeys<DatabasePermission>("$key/roles", adc)
            val exposeTo = cmd.applicationFiles.associateSubKeys<String>("$key/exposeTo", adc)
            val flavor: DatabaseFlavor = adc.getOrNull("$key/flavor") ?: defaultDb.flavor
            val instance = findInstance(adc, cmd, "$key/instance", flavor.defaultFallback)
            val value: String = adc.getOrNull("$key/id") ?: ""

            val instanceName = instance?.name ?: defaultDb.instance.name
            val instanceFallback = instance?.fallback ?: flavor.defaultFallback
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
                exposeTo = defaultDb.exposeTo + exposeTo,
                applicationLabel = adc.getOrNull("$key/applicationLabel")
            )
        }
    }

    private fun findInstance(
        adc: AuroraDeploymentSpec,
        cmd: AuroraContextCommand,
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

    private fun createExpandedDbHandlers(
        db: String,
        applicationFiles: List<AuroraConfigFile>
    ): List<AuroraConfigFieldHandler> {

        val mainHandlers = listOf(
            AuroraConfigFieldHandler("$db/enabled", defaultValue = true, validator = { it.boolean() }),
            AuroraConfigFieldHandler("$db/generate", validator = { it.boolean() }),
            AuroraConfigFieldHandler("$db/name"),
            AuroraConfigFieldHandler("$db/applicationLabel"),
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
            AuroraConfigFieldHandler(
                "$databaseDefaultsKey/generate",
                validator = { it.boolean() },
                defaultValue = true
            ),
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

    private fun findRolesHandlers(
        key: String,
        applicationFiles: List<AuroraConfigFile>
    ): List<AuroraConfigFieldHandler> {
        return applicationFiles.findSubHandlers("$key/roles", validatorFn = {
            { node ->
                node.oneOf(DatabasePermission.values().map { it.toString() })
            }
        })
    }

    private fun findInstanceHandlers(
        key: String,
        applicationFiles: List<AuroraConfigFile>
    ): List<AuroraConfigFieldHandler> {
        return applicationFiles.findSubKeys("$key/instance").flatMap {
            if (it == "labels") {
                applicationFiles.findSubHandlers("$key/instance/$it")
            } else {
                listOf(AuroraConfigFieldHandler("$key/instance/$it"))
            }
        }
    }

    private fun findExposeToHandlers(
        key: String,
        validRoles: Set<String>,
        applicationFiles: List<AuroraConfigFile>
    ): List<AuroraConfigFieldHandler> {
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
    val instance: DatabaseInstance,
    val applicationLabel: String? = null
)

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

fun createDbEnv(name: String, envName: String = name): List<Pair<String, String>> {
    val path = "$secretsPath/${name.toLowerCase()}"
    val envName = envName.replace("-", "_").toUpperCase()

    return listOf(
        envName to "$path/info",
        "${envName}_PROPERTIES" to "$path/db.properties"
    )
}

object DbhSecretGenerator {

    fun createInfoFile(dbhSchema: DbhSchema): String {

        val infoFile = mapOf(
            "database" to mapOf(
                "id" to dbhSchema.id,
                "name" to dbhSchema.username,
                "createdDate" to null,
                "lastUsedDate" to null,
                "host" to dbhSchema.databaseInstance.host,
                "port" to dbhSchema.databaseInstance.port,
                "service" to dbhSchema.service,
                "jdbcUrl" to dbhSchema.jdbcUrl,
                "users" to listOf(
                    mapOf(
                        "username" to dbhSchema.username,
                        "password" to dbhSchema.password,
                        "type" to dbhSchema.userType
                    )
                ),
                "labels" to dbhSchema.labels
            )
        )
        return jacksonObjectMapper().writeValueAsString(infoFile)
    }

    fun createConnectionProperties(dbhSchema: DbhSchema): String {
        return Properties().run {
            put("jdbc.url", dbhSchema.jdbcUrl)
            put("jdbc.user", dbhSchema.username)
            put("jdbc.password", dbhSchema.password)

            val bos = ByteArrayOutputStream()
            store(bos, "")
            bos.toString("UTF-8")
        }
    }

    fun createDbhSecret(
        schemaProvisionResult: SchemaProvisionResult,
        secretName: String,
        secretNamespace: String
    ): Secret {
        val connectionProperties = createConnectionProperties(schemaProvisionResult.dbhSchema)
        val infoFile = createInfoFile(schemaProvisionResult.dbhSchema)

        return newSecret {
            metadata {
                name = secretName
                namespace = secretNamespace
                labels = mapOf("dbhId" to schemaProvisionResult.dbhSchema.id)
            }
            data = mapOf(
                "db.properties" to connectionProperties,
                "id" to schemaProvisionResult.dbhSchema.id,
                "info" to infoFile,
                "jdbcurl" to schemaProvisionResult.dbhSchema.jdbcUrl,
                "name" to schemaProvisionResult.dbhSchema.username
            ).mapValues { it.value.toByteArray() }.mapValues { Base64.encodeBase64String(it.value) }
        }
    }
}
