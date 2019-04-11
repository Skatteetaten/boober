package no.skatteetaten.aurora.boober.service.internal

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.kubernetes.api.model.Secret
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpecInternal
import no.skatteetaten.aurora.boober.model.Database
import no.skatteetaten.aurora.boober.model.Mount
import no.skatteetaten.aurora.boober.model.MountType
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DbhSchema
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaProvisionResult
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaProvisionResults
import java.io.ByteArrayOutputStream
import java.util.Properties

object DbhSecretGenerator {

    @JvmStatic
    fun create(
        appName: String,
        schemaProvisionResults: SchemaProvisionResults,
        labels: Map<String, String>,
        ownerReference: OwnerReference,
        namespace: String
    ): List<Secret> {

        return schemaProvisionResults.results.map {
            createDbhSecret(it, appName, labels, ownerReference, namespace)
        }
    }

    private fun createDbhSecret(
        it: SchemaProvisionResult,
        appName: String,
        labels: Map<String, String>,
        ownerReference: OwnerReference,
        namespace: String
    ): Secret {
        val connectionProperties = createConnectionProperties(it.dbhSchema)
        val infoFile = createInfoFile(it.dbhSchema)

        return SecretGenerator.create(
            secretName = it.createName(appName),
            secretLabels = labels,
            secretData = mapOf(
                "db.properties" to connectionProperties,
                "id" to it.dbhSchema.id,
                "info" to infoFile,
                "jdbcurl" to it.dbhSchema.jdbcUrl,
                "name" to it.dbhSchema.username
            )
                .mapValues { it.value.toByteArray() },
            ownerReference = ownerReference,
            secretNamespace = namespace
        )
    }

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

    private fun createConnectionProperties(dbhSchema: DbhSchema): String {
        return Properties().run {
            put("jdbc.url", dbhSchema.jdbcUrl)
            put("jdbc.user", dbhSchema.username)
            put("jdbc.password", dbhSchema.password)

            val bos = ByteArrayOutputStream()
            store(bos, "")
            bos.toString("UTF-8")
        }
    }
}

fun Database.createDbEnv(envName: String): List<Pair<String, String>> {
    val path = "/u01/secrets/app/${this.name.toLowerCase()}-db"
    val envName = envName.replace("-", "_").toUpperCase()

    return listOf(
        envName to "$path/info",
        "${envName}_PROPERTIES" to "$path/db.properties"
    )
}

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
}

// TODO: Skal denne ha startsWith?
fun SchemaProvisionResult.createName(appName: String) =
    "$appName-${this.request.details.schemaName}-db".replace("_", "-").toLowerCase()
