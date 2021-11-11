package no.skatteetaten.aurora.boober.feature

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
import no.skatteetaten.aurora.boober.model.findSubHandlers
import no.skatteetaten.aurora.boober.model.findSubKeys
import no.skatteetaten.aurora.boober.model.findSubKeysExpanded
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeployment
import no.skatteetaten.aurora.boober.service.ProvisioningException
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseEngine
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseSchemaProvisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DbhSchema
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaForAppRequest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaIdRequest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaProvisionRequest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaRequestDetails
import no.skatteetaten.aurora.boober.utils.ConditionalOnPropertyMissingOrEmpty
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.boolean
import no.skatteetaten.aurora.boober.utils.ensureStartWith
import no.skatteetaten.aurora.boober.utils.findResourcesByType
import no.skatteetaten.aurora.boober.utils.oneOf
import org.apache.commons.codec.binary.Base64
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.util.Properties
import kotlin.reflect.KClass

private val logger = KotlinLogging.logger { }

private const val DATABASE_CONTEXT_KEY = "databases"

private val FeatureContext.databases: List<Database> get() = this.getContextKey(DATABASE_CONTEXT_KEY)

@ConditionalOnPropertyMissingOrEmpty("integrations.dbh.url")
@Service
class DatabaseDisabledFeature(
    @Value("\${openshift.cluster}") cluster: String
) : DatabaseFeatureTemplate(cluster) {

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        context: FeatureContext
    ): List<Exception> {
        if (context.databases.isNotEmpty()) {
            return listOf(IllegalArgumentException("Databases are not supported in this cluster"))
        }
        return emptyList()
    }
}

@Service
@ConditionalOnProperty("integrations.dbh.url")
class DatabaseFeature(
    val databaseSchemaProvisioner: DatabaseSchemaProvisioner,
    val userDetailsProvider: UserDetailsProvider,
    @Value("\${openshift.cluster}") cluster: String
) : DatabaseFeatureTemplate(cluster) {

    private fun SchemaProvisionRequest.isAppRequestWithoutGenerate() = this is SchemaForAppRequest && !this.generate

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        context: FeatureContext
    ): List<Exception> {
        val db = context.databases
        val databases = db.createSchemaRequests(adc)
        if (!fullValidation || adc.cluster != cluster || databases.isEmpty()) {
            return emptyList()
        }

        return databases.filter { it is SchemaIdRequest || it.isAppRequestWithoutGenerate() }
            .mapNotNull { request ->
                try {
                    val schema = databaseSchemaProvisioner.findSchema(request)
                        ?: databaseSchemaProvisioner.findCooldownSchemaIfTryReuseEnabled(request)

                    when {
                        schema == null -> ProvisioningException(
                            "Could not find schema with name=${request.details.schemaName}"
                        )
                        schema.affiliation != request.details.affiliation -> ProvisioningException(
                            "Schema with id=${schema.id} is located in the affiliation=${schema.affiliation}, " +
                                "current affiliation=${request.details.affiliation}. " +
                                "Using schema with id across affiliations is not allowed"
                        )
                        else -> null
                    }
                } catch (e: Exception) {
                    e
                }
            }
    }

    override fun generate(adc: AuroraDeploymentSpec, context: FeatureContext): Set<AuroraResource> {
        val databases = context.databases

        val schemaRequests = databases.createSchemaRequests(adc)

        if (schemaRequests.isEmpty()) return emptySet()

        return schemaRequests.provisionSchemasAndAssociateWithRequest()
            .createDbhSecrets(adc)
            .generateAuroraResources()
            .toSet()
    }

    override fun modify(
        adc: AuroraDeploymentSpec,
        resources: Set<AuroraResource>,
        context: FeatureContext
    ) {

        val databases = context.databases
        if (databases.isEmpty()) return

        resources.attachDbSecrets(databases, adc.name, this::class)
        resources.addDatabaseIdsToApplicationDeployment()
    }

    private fun Set<AuroraResource>.addDatabaseIdsToApplicationDeployment() {
        val databaseIds = this.findResourcesByType<Secret>().mapNotNull {
            it.metadata?.labels?.get("dbhId")
        }

        this.filter { it.resource.kind == "ApplicationDeployment" }
            .map {
                modifyResource(it, "Added databaseId")
                val ad: ApplicationDeployment = it.resource as ApplicationDeployment
                ad.spec.databases = databaseIds
            }
    }

    private fun Set<AuroraResource>.attachDbSecrets(
        databases: List<Database>,
        appName: String,
        feature: KClass<out Feature>
    ) {
        val firstEnv = databases.firstOrNull()?.let {
            createDbEnv("${it.name}-db", "db")
        }
        val dbEnv = databases.flatMap { createDbEnv("${it.name}-db") }
            .addIfNotNull(firstEnv).toMap().toEnvVars()

        val volumeAndMounts = databases.map { it.createDatabaseVolumesAndMounts(appName) }

        val volumes = volumeAndMounts.map { it.first }
        val volumeMounts = volumeAndMounts.map { it.second }

        this.addVolumesAndMounts(dbEnv, volumes, volumeMounts, feature.java)
    }

    private fun List<SchemaProvisionRequest>.provisionSchemasAndAssociateWithRequest() =
        this.associateWith {
            databaseSchemaProvisioner.provisionSchema(it)
        }

    private fun Map<SchemaProvisionRequest, DbhSchema>.createDbhSecrets(adc: AuroraDeploymentSpec) =
        this.map { (request, dbhSchema) ->
            DbhSecretGenerator.createDbhSecret(
                dbhSchema = dbhSchema,
                secretName = request.getSecretName(prefix = adc.name),
                secretNamespace = adc.namespace
            )
        }

    fun SchemaProvisionRequest.getSecretName(prefix: String): String {
        val secretName = this.details.schemaName.replace("_", "-").lowercase().ensureStartWith(prefix, "-")
        return "$secretName-db"
    }

    fun Database.createDatabaseVolumesAndMounts(appName: String): Pair<Volume, VolumeMount> {
        val mountName = "${this.name}-db".lowercase()
        val volumeName = mountName.replace("_", "-").lowercase().ensureStartWith(appName, "-")

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

    fun List<Database>.createSchemaRequests(adc: AuroraDeploymentSpec): List<SchemaProvisionRequest> {
        return this.map {
            val details = it.createSchemaDetails(adc.affiliation)
            if (it.id != null) {
                SchemaIdRequest(
                    id = it.id,
                    details = details,
                    tryReuse = it.tryReuse
                )
            } else {
                SchemaForAppRequest(
                    environment = adc.envName,
                    application = it.applicationLabel ?: adc.name,
                    details = details,
                    generate = it.generate,
                    tryReuse = it.tryReuse,
                    user = userDetailsProvider.getAuthenticatedUser()
                )
            }
        }
    }
}

abstract class DatabaseFeatureTemplate(val cluster: String) : Feature {

    override fun createContext(
        spec: AuroraDeploymentSpec,
        cmd: AuroraContextCommand,
        validationContext: Boolean
    ): Map<String, Any> {
        return mapOf("databases" to findDatabases(spec))
    }

    val databaseDefaultsKey = "databaseDefaults"

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        val dbHandlers = findDbHandlers(cmd.applicationFiles)

        val dbDefaultsHandlers = findDbDefaultHandlers(cmd.applicationFiles)

        return (
            dbDefaultsHandlers + dbHandlers + listOf(
                AuroraConfigFieldHandler(
                    "database",
                    validator = { it.boolean() },
                    defaultValue = false,
                    canBeSimplifiedConfig = true
                )
            )
            ).toSet()
    }

    fun findDatabases(adc: AuroraDeploymentSpec): List<Database> {
        val defaultFlavor: DatabaseFlavor = adc["$databaseDefaultsKey/flavor"]
        val defaultInstance = findInstance(adc, "$databaseDefaultsKey/instance", defaultFlavor.defaultFallback)
            ?: DatabaseInstance(fallback = defaultFlavor.defaultFallback)

        val defaultDb = Database(
            name = adc["$databaseDefaultsKey/name"],
            flavor = defaultFlavor,
            generate = adc["$databaseDefaultsKey/generate"],
            instance = defaultInstance.copy(labels = defaultInstance.labels + mapOf("affiliation" to adc.affiliation)),
            tryReuse = adc["$databaseDefaultsKey/tryReuse"]
        )
        if (adc.isSimplifiedAndEnabled("database")) {
            return listOf(defaultDb)
        }
        return adc.getSubKeyValues("database").mapNotNull { db -> findDatabase(db, adc, defaultDb) }
    }

    private fun findDatabase(
        db: String,
        adc: AuroraDeploymentSpec,
        defaultDb: Database
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
            val flavor: DatabaseFlavor = adc.getOrNull("$key/flavor") ?: defaultDb.flavor
            val instance = findInstance(adc, "$key/instance", flavor.defaultFallback)
            val value: String = adc.getOrNull("$key/id") ?: ""
            val tryReuse: Boolean = adc.getOrDefault("database", db, "tryReuse")

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
                applicationLabel = adc.getOrNull("$key/applicationLabel"),
                tryReuse = tryReuse
            )
        }
    }

    private fun findInstance(
        adc: AuroraDeploymentSpec,
        key: String,
        defaultFallback: Boolean
    ): DatabaseInstance? {

        if (!adc.hasSubKeys(key)) {
            return null
        }
        return DatabaseInstance(
            name = adc.getOrNull("$key/name"),
            fallback = adc.getOrNull("$key/fallback") ?: defaultFallback,
            labels = adc.getSubKeys("$key/labels").mapValues { it.value.value.textValue() }
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
            AuroraConfigFieldHandler("$db/tryReuse", validator = { it.boolean() }),
            AuroraConfigFieldHandler(
                "$db/flavor", validator = { node ->
                    node?.oneOf(DatabaseFlavor.values().map { it.toString() })
                }
            )
        )

        val instanceHandlers = findInstanceHandlers(db, applicationFiles)

        return mainHandlers + instanceHandlers
    }

    fun findDbDefaultHandlers(applicationFiles: List<AuroraConfigFile>): List<AuroraConfigFieldHandler> {

        val databaseDefaultHandler = listOf(
            AuroraConfigFieldHandler(
                "$databaseDefaultsKey/flavor",
                defaultValue = DatabaseFlavor.ORACLE_MANAGED,
                validator = { node ->
                    node.oneOf(DatabaseFlavor.values().map { it.toString() })
                }
            ),
            AuroraConfigFieldHandler(
                "$databaseDefaultsKey/generate",
                validator = { it.boolean() },
                defaultValue = true
            ),
            AuroraConfigFieldHandler(
                "$databaseDefaultsKey/tryReuse",
                defaultValue = false,
                validator = { it.boolean() }
            ),
            AuroraConfigFieldHandler(
                "$databaseDefaultsKey/name",
                defaultValue = "@name@"
            ) // må vi ha på en validator her?
        )

        val instanceHandlers = findInstanceHandlers(databaseDefaultsKey, applicationFiles)

        return listOf<AuroraConfigFieldHandler>() + databaseDefaultHandler + instanceHandlers
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

data class Database(
    val name: String,
    val id: String? = null,
    val flavor: DatabaseFlavor,
    val generate: Boolean,
    val tryReuse: Boolean,
    val instance: DatabaseInstance,
    val applicationLabel: String? = null
)

data class DatabaseInstance(
    val name: String? = null,
    val fallback: Boolean = false,
    val labels: Map<String, String> = emptyMap()
)

fun Database.createSchemaDetails(affiliation: String) =
    SchemaRequestDetails(
        schemaName = this.name.lowercase(),
        databaseInstance = this.instance,
        affiliation = affiliation,
        engine = this.flavor.engine
    )

fun createDbEnv(name: String, envName: String = name): List<Pair<String, String>> {
    val path = "$secretsPath/${name.lowercase()}"
    val envName = envName.replace("-", "_").uppercase()

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
        dbhSchema: DbhSchema,
        secretName: String,
        secretNamespace: String
    ): Secret {
        val connectionProperties = createConnectionProperties(dbhSchema)
        val infoFile = createInfoFile(dbhSchema)

        return newSecret {
            metadata {
                name = secretName
                namespace = secretNamespace
                labels = mapOf("dbhId" to dbhSchema.id)
            }
            data = mapOf(
                "db.properties" to connectionProperties,
                "id" to dbhSchema.id,
                "info" to infoFile,
                "jdbcurl" to dbhSchema.jdbcUrl,
                "name" to dbhSchema.username
            ).mapValues { it.value.toByteArray() }.mapValues { Base64.encodeBase64String(it.value) }
        }
    }
}
