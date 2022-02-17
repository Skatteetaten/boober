package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newSecret
import io.fabric8.kubernetes.api.model.Secret
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.Paths
import no.skatteetaten.aurora.boober.model.findSubHandlers
import no.skatteetaten.aurora.boober.model.findSubKeys
import no.skatteetaten.aurora.boober.model.findSubKeysExpanded
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseEngine
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DbhSchema
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaForAppRequest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaIdRequest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaProvisionRequest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaRequestDetails
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.boolean
import no.skatteetaten.aurora.boober.utils.ensureStartWith
import no.skatteetaten.aurora.boober.utils.oneOf
import org.apache.commons.codec.binary.Base64
import java.io.ByteArrayOutputStream
import java.util.Properties

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

internal fun dbHandlers(cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {

    val dbHandlers = findDbHandlers(cmd.applicationFiles)
    val dbDefaultsHandlers = findDbDefaultHandlers(cmd.applicationFiles)

    return listOf(
        dbDefaultsHandlers,
        dbHandlers,
        listOf(
            AuroraConfigFieldHandler(
                "database",
                validator = { it.boolean() },
                defaultValue = false,
                canBeSimplifiedConfig = true
            )
        )
    ).flatten().toSet()
}

internal fun findDbHandlers(applicationFiles: List<AuroraConfigFile>) =
    applicationFiles.findSubKeysExpanded("database").flatMap { db ->
        val expandedDbKeys = applicationFiles.findSubKeys(db)
        if (expandedDbKeys.isEmpty()) {
            listOf(AuroraConfigFieldHandler(db))
        } else {
            createExpandedDbHandlers(db, applicationFiles)
        }
    }

internal fun findDbDefaultHandlers(applicationFiles: List<AuroraConfigFile>): List<AuroraConfigFieldHandler> {

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

internal fun findDatabases(adc: AuroraDeploymentSpec): List<Database> {

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

    return if (adc.isSimplifiedAndEnabled("database")) listOf(defaultDb)
    else adc.getSubKeyValues("database").mapNotNull { db -> findDatabase(db, adc, defaultDb) }
}

internal fun createDbEnv(name: String, envName: String = name): List<Pair<String, String>> {

    val path = "${Paths.secretsPath}/${name.lowercase()}"
    val envName = envName.replace("-", "_").uppercase()

    return listOf(
        envName to "$path/info",
        "${envName}_PROPERTIES" to "$path/db.properties"
    )
}

internal fun List<Database>.createSchemaRequests(
    userDetailsProvider: UserDetailsProvider,
    adc: AuroraDeploymentSpec
): List<SchemaProvisionRequest> = map {
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

internal fun SchemaProvisionRequest.getSecretName(prefix: String): String {
    val secretName = details.schemaName.replace("_", "-").lowercase().ensureStartWith(prefix, "-")
    return "$secretName-db"
}

internal fun Database.createSchemaDetails(affiliation: String) =
    SchemaRequestDetails(
        schemaName = name.lowercase(),
        databaseInstance = instance,
        affiliation = affiliation,
        engine = flavor.engine
    )

internal object DbhSecretGenerator {

    private fun createInfoFile(dbhSchema: DbhSchema): String {
        val infoFile = with(dbhSchema) {
            mapOf(
                "database" to mapOf(
                    "id" to id,
                    "name" to username,
                    "createdDate" to null,
                    "lastUsedDate" to null,
                    "host" to databaseInstance.host,
                    "port" to databaseInstance.port,
                    "service" to service,
                    "jdbcUrl" to jdbcUrl,
                    "users" to listOf(
                        mapOf(
                            "username" to username,
                            "password" to password,
                            "type" to userType
                        )
                    ),
                    "labels" to labels
                )
            )
        }
        return jacksonObjectMapper().writeValueAsString(infoFile)
    }

    private fun createConnectionProperties(dbhSchema: DbhSchema) = Properties().run {

        put("jdbc.url", dbhSchema.jdbcUrl)
        put("jdbc.user", dbhSchema.username)
        put("jdbc.password", dbhSchema.password)

        val bos = ByteArrayOutputStream()
        store(bos, "")
        bos.toString("UTF-8")
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

private fun findInstanceHandlers(
    key: String,
    applicationFiles: List<AuroraConfigFile>
) = applicationFiles.findSubKeys("$key/instance").flatMap {
    if (it == "labels") {
        applicationFiles.findSubHandlers("$key/instance/$it")
    } else {
        listOf(AuroraConfigFieldHandler("$key/instance/$it"))
    }
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
        val instanceLabels = emptyMap<String, String>()
            .addIfNotNull(defaultDb.instance.labels)
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
