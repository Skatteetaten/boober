package no.skatteetaten.aurora.boober.service.internal

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.fabric8.kubernetes.api.model.Secret
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DbhSchema
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaProvisionResults
import java.io.ByteArrayOutputStream
import java.util.Properties

object DbhSecretGenerator {

    @JvmStatic
    fun create(appName: String, schemaProvisionResults: SchemaProvisionResults, labels: Map<String, String>): List<Secret> {

        return schemaProvisionResults.results.map {

            val connectionProperties = createConnectionProperties(it.dbhSchema)
            val infoFile = createInfoFile(it.dbhSchema)

            SecretGenerator.create(
                secretName = "${appName}-${it.request.schemaName}-db".replace("_", "-"),
                secretLabels = labels,
                secretData = mapOf(
                    "db.properties" to connectionProperties,
                    "id" to it.dbhSchema.id,
                    "info" to infoFile,
                    "jdbcurl" to it.dbhSchema.jdbcUrl,
                    "name" to it.dbhSchema.username)
                    .mapValues { it.value.toByteArray() }
            )
        }
    }

    private fun createInfoFile(dbhSchema: DbhSchema): String {

        val infoFile = mapOf("database" to mapOf(
            "id" to dbhSchema.id,
            "name" to dbhSchema.username,
            "createdDate" to null,
            "lastUsedDate" to null,
            "host" to dbhSchema.databaseInstance.host,
            "port" to dbhSchema.databaseInstance.port,
            "service" to dbhSchema.service,
            "jdbcUrl" to dbhSchema.jdbcUrl,
            "users" to listOf(mapOf(
                "username" to dbhSchema.username,
                "password" to dbhSchema.password,
                "type" to dbhSchema.userType
            )),
            "labels" to dbhSchema.labels
        ))
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