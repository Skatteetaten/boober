package no.skatteetaten.aurora.boober.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate

interface SchemaProvisionRequest

data class SchemaIdRequest(val id: String) : SchemaProvisionRequest

data class SchemaForAppRequest(val id: String) : SchemaProvisionRequest

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
        get() = items.get(0)
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
        else -> throw IllegalArgumentException("Unsupported type ${it::class.qualifiedName}")
    }

    private fun provisionFromId(request: SchemaIdRequest): SchemaProvisionResult {

        val dbhSchema = getSchemaById(request.id)
        return SchemaProvisionResult(request, dbhSchema)
    }

    private fun getSchemaById(id: String): DbhSchema {

        val response: ResponseEntity<DbApiEnvelope> = try {
            restTemplate.getForEntity("${dbhUrl}/api/v1/schema/$id", DbApiEnvelope::class.java)
        } catch (e: Exception) {
            throw ProvisioningException("Unable to get information on schema with id $id", e)
        }

        val dbApiEnvelope: DbApiEnvelope = response.body
        return dbApiEnvelope.dbhSchema
    }
}