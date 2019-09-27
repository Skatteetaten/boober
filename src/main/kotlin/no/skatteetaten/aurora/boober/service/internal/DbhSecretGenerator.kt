package no.skatteetaten.aurora.boober.service.internal

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newSecret
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.kubernetes.api.model.Secret
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DbhSchema
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaProvisionResult
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaProvisionResults
import org.apache.commons.codec.binary.Base64
import java.io.ByteArrayOutputStream
import java.util.Properties

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





// TODO: Skal denne ha startsWith?
fun SchemaProvisionResult.createName(appName: String) =
        "$appName-${this.request.details.schemaName}-db".replace("_", "-").toLowerCase()
