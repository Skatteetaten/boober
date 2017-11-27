package no.skatteetaten.aurora.boober.service.internal

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.DbhSchema
import no.skatteetaten.aurora.boober.service.SchemaProvisionResults
import no.skatteetaten.aurora.boober.service.VelocityTemplateJsonService
import java.io.ByteArrayOutputStream
import java.util.*

class DbhSecretGenerator(private val velocityTemplateJsonService: VelocityTemplateJsonService) {

    object Base64 {
        fun encode(str: String): String = org.apache.commons.codec.binary.Base64.encodeBase64String(str.toByteArray())
    }

    fun generateSecretsForSchemas(deploymentSpec: AuroraDeploymentSpec, schemaProvisionResults: SchemaProvisionResults): List<JsonNode> {

        return schemaProvisionResults.results.map {
            val connectionProperties = createConnectionProperties(it.dbhSchema)
            val responseText = it.responseText
            velocityTemplateJsonService.renderToJson("secret.json", mapOf(
                    "base64" to Base64,
                    "deploymentSpec" to deploymentSpec,
                    "dbhSchema" to it.dbhSchema,
                    "connectionProperties" to connectionProperties,
                    "responseText" to responseText
            ))
        }
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