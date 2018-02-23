package no.skatteetaten.aurora.boober.service.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.OpenShiftObjectLabelService
import no.skatteetaten.aurora.boober.service.VelocityTemplateJsonService
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DbhSchema
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaProvisionResults
import java.io.ByteArrayOutputStream
import java.util.Properties

class DbhSecretGenerator(
    private val velocityTemplateJsonService: VelocityTemplateJsonService,
    private val openShiftObjectLabelService: OpenShiftObjectLabelService,
    private val mapper: ObjectMapper
) {

    object Base64 {
        fun encode(str: String): String = org.apache.commons.codec.binary.Base64.encodeBase64String(str.toByteArray())
    }

    fun generateSecretsForSchemas(deployId: String, deploymentSpec: AuroraDeploymentSpec,
                                  schemaProvisionResults: SchemaProvisionResults): List<JsonNode> {

        val labels = openShiftObjectLabelService.createCommonLabels(deploymentSpec, deployId)
        return schemaProvisionResults.results.map {

            val connectionProperties = createConnectionProperties(it.dbhSchema)
            val infoFile = createInfoFile(it.dbhSchema)
            velocityTemplateJsonService.renderToJson("secret.json", mapOf(
                "base64" to Base64,
                "labels" to labels,
                "deploymentSpec" to deploymentSpec,
                "dbhSchema" to it.dbhSchema,
                "request" to it.request,
                "connectionProperties" to connectionProperties,
                "infoFile" to infoFile
            ))
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
        return mapper.writeValueAsString(infoFile)
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