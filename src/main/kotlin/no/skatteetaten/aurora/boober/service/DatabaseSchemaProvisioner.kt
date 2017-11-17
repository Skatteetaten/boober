package no.skatteetaten.aurora.boober.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import org.springframework.web.util.UriUtils

interface SchemaProvisionRequest

data class SchemaIdRequest(val id: String) : SchemaProvisionRequest

data class SchemaForAppRequest(
        val affiliation: String,
        val environment: String,
        val application: String,
        val schemaName: String
) : SchemaProvisionRequest

data class SchemaProvisionResult(val request: SchemaProvisionRequest, val dbhSchema: DbhSchema)

data class SchemaProvisionResults(val results: List<SchemaProvisionResult>)

data class DbhSchema(
        val jdbcUrl: String
)

data class DbApiEnvelope(
        val status: String,
        val items: List<DbhSchema> = listOf()
) {
    val dbhSchema: DbhSchema
        get() {
            if (items.size != 1) {
                throw IllegalArgumentException("Response should contain exactly one entry for the given query")
            }
            return items.get(0)
        }
}

@Service
class DatabaseSchemaProvisioner(
        val restTemplate: RestTemplate,
        @Value("\${boober.dbh:http://localhost:8080}") val dbhUrl: String
) {
    fun provisionSchemas(schemaProvisionRequests: List<SchemaProvisionRequest>): SchemaProvisionResults {

        if (schemaProvisionRequests.isEmpty()) throw IllegalArgumentException("SchemaProvisionRequest cannot be empty")

        val results: List<SchemaProvisionResult> = schemaProvisionRequests.map(this::provisionSchema)
        return SchemaProvisionResults(results)
    }

    fun provisionSchema(it: SchemaProvisionRequest): SchemaProvisionResult = when (it) {
        is SchemaIdRequest -> provisionFromId(it)
        is SchemaForAppRequest -> provisionForApplication(it)
        else -> throw IllegalArgumentException("Unsupported type ${it::class.qualifiedName}")
    }

    private fun provisionFromId(request: SchemaIdRequest): SchemaProvisionResult {

        val dbhSchema = findSchemaById(request.id)
        return SchemaProvisionResult(request, dbhSchema)
    }


    private fun provisionForApplication(request: SchemaForAppRequest): SchemaProvisionResult {

        val labels = mapOf(
                "affiliation" to request.affiliation,
                "environment" to request.environment,
                "application" to request.application,
                "name" to request.schemaName
        )
        val dbhSchema = findOrCreateSchemaByLabels(labels)
        return SchemaProvisionResult(request, dbhSchema)
    }

    private fun findSchemaById(id: String): DbhSchema {

        val response: ResponseEntity<DbApiEnvelope> = try {
            restTemplate.getForEntity("{0}/api/v1/schema/{1}", DbApiEnvelope::class.java, dbhUrl, id)
        } catch (e: Exception) {
            throw ProvisioningException("Unable to get information on schema with id $id", e)
        }

        val dbApiEnvelope: DbApiEnvelope = response.body
        return dbApiEnvelope.dbhSchema
    }

    private fun findOrCreateSchemaByLabels(labels: Map<String, String>): DbhSchema {

        return findSchemaByLabels(labels) ?: return createSchema(labels)
    }

    private fun findSchemaByLabels(labels: Map<String, String>): DbhSchema? {
        val labelsString = labels.map { "${it.key}=${it.value}" }.joinToString(",")
        val response: ResponseEntity<DbApiEnvelope> = try {
            val encodedLabelsString = UriUtils.encode(labelsString, "UTF-8")
            restTemplate.getForEntity("{0}/api/v1/schema/labels={1}", DbApiEnvelope::class.java, dbhUrl, encodedLabelsString)
        } catch (e: Exception) {
            throw ProvisioningException("Unable to get information on schema with labels ${labelsString}", e)
        }

        val dbApiEnvelope: DbApiEnvelope = response.body
        if (dbApiEnvelope.items.isEmpty()) {
            return null
        }
        return dbApiEnvelope.dbhSchema
    }

    private fun createSchema(labels: Map<String, String>): DbhSchema {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}