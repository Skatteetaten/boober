package no.skatteetaten.aurora.boober.service.internal

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.DbhSchema
import no.skatteetaten.aurora.boober.service.OpenShiftObjectLabelService
import no.skatteetaten.aurora.boober.service.SchemaProvisionResults
import no.skatteetaten.aurora.boober.service.VelocityTemplateJsonService
import java.io.ByteArrayOutputStream
import java.util.*

class DbhSecretGenerator(
        private val velocityTemplateJsonService: VelocityTemplateJsonService,
        private val openShiftObjectLabelService: OpenShiftObjectLabelService
) {

    object Base64 {
        fun encode(str: String): String = org.apache.commons.codec.binary.Base64.encodeBase64String(str.toByteArray())
    }

    fun generateSecretsForSchemas(deployId: String, deploymentSpec: AuroraDeploymentSpec,
                                  schemaProvisionResults: SchemaProvisionResults): List<JsonNode> {

        val labels = openShiftObjectLabelService.createCommonLabels(deploymentSpec, deployId)
        return schemaProvisionResults.results.map {

            val connectionProperties = createConnectionProperties(it.dbhSchema)
            val responseText = it.responseText
            velocityTemplateJsonService.renderToJson("secret.json", mapOf(
                    "base64" to Base64,
                    "labels" to labels,
                    "deploymentSpec" to deploymentSpec,
                    "dbhSchema" to it.dbhSchema,
                    "request" to it.request,
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