package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate

interface SchemaProvisionRequest {
    val schemaName: String
}

data class SchemaIdRequest(val id: String, override val schemaName: String) : SchemaProvisionRequest

data class SchemaForAppRequest(
        val affiliation: String,
        val environment: String,
        val application: String,
        override val schemaName: String
) : SchemaProvisionRequest

data class SchemaProvisionResult(val request: SchemaProvisionRequest, val dbhSchema: DbhSchema, val responseText: String)

data class SchemaProvisionResults(val results: List<SchemaProvisionResult>)

data class DbhUser(val username: String, val password: String, val type: String)

data class DbhSchema(
        val id: String,
        val type: String,
        val jdbcUrl: String,
        val labels: Map<String, String> = mapOf(),
        private val users: List<DbhUser> = listOf()
) {
    val name: String
        get() = labels.get("name")!!

    val affiliation: String
        get() = labels.get("affiliation")!!

    val username: String
        get() = users.firstOrNull()?.username ?: ""

    val password: String
        get() = users.firstOrNull()?.password ?: ""
}

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
        val mapper: ObjectMapper,
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

        val (dbhSchema, responseText) = findSchemaById(request.id)
        return SchemaProvisionResult(request, dbhSchema, responseText)
    }


    private fun provisionForApplication(request: SchemaForAppRequest): SchemaProvisionResult {

        val labels = mapOf(
                "affiliation" to request.affiliation,
                "environment" to request.environment,
                "application" to request.application,
                "name" to request.schemaName
        )
        val (dbhSchema, responseText) = findOrCreateSchemaByLabels(labels)
        return SchemaProvisionResult(request, dbhSchema, responseText)
    }

    private fun findSchemaById(id: String): Pair<DbhSchema, String> {

        val response: ResponseEntity<JsonNode> = try {
            restTemplate.getForEntity("{0}/api/v1/schema/{1}", JsonNode::class.java, dbhUrl, id)
        } catch (e: Exception) {
            throw ProvisioningException("Unable to get information on schema with id $id", e)
        }
        return parseResponseFailIfEmpty(response)
    }

    private fun findOrCreateSchemaByLabels(labels: Map<String, String>): Pair<DbhSchema, String> {

        return findSchemaByLabels(labels) ?: return createSchema(labels)
    }

    private fun findSchemaByLabels(labels: Map<String, String>): Pair<DbhSchema, String>? {
        val labelsString = labels.map { "${it.key}=${it.value}" }.joinToString(",")
        val response: ResponseEntity<JsonNode> = try {
            restTemplate.getForEntity("{0}/api/v1/schema/?labels={1}", JsonNode::class.java, dbhUrl, labelsString)
        } catch (e: Exception) {
            throw ProvisioningException("Unable to get information on schema with labels ${labelsString}", e)
        }

        return parseResponse(response)
    }

    private fun createSchema(labels: Map<String, String>): Pair<DbhSchema, String> {

        val payload = mapOf("labels" to labels)
        val response: ResponseEntity<JsonNode> = try {
            restTemplate.postForEntity("{0}/api/v1/schema/", payload, JsonNode::class.java, dbhUrl)
        } catch (e: Exception) {
            val labelsString = labels.map { "${it.key}=${it.value}" }.joinToString(",")
            throw ProvisioningException("Unable to create database schema for application $labelsString", e)
        }

        return parseResponseFailIfEmpty(response)
    }

    private fun parseResponseFailIfEmpty(response: ResponseEntity<JsonNode>): Pair<DbhSchema, String> {
        return parseResponse(response) ?: throw ProvisioningException("Expected dbh response to contain schema info")
    }

    private fun parseResponse(response: ResponseEntity<JsonNode>): Pair<DbhSchema, String>? {

        val responseBody = response.body.toString()
        val dbApiEnvelope: DbApiEnvelope = mapper.readValue(responseBody, DbApiEnvelope::class.java)
        if (dbApiEnvelope.items.isEmpty()) {
            return null
        }
        return Pair(dbApiEnvelope.dbhSchema, responseBody)
    }
}