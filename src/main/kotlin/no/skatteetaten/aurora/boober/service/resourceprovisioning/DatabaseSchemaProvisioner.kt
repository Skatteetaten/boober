package no.skatteetaten.aurora.boober.service.resourceprovisioning

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import no.skatteetaten.aurora.boober.ServiceTypes
import no.skatteetaten.aurora.boober.TargetService
import no.skatteetaten.aurora.boober.model.DatabaseInstance
import no.skatteetaten.aurora.boober.service.ProvisioningException
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.utils.logger
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate

sealed class SchemaProvisionRequest {

    abstract val details: SchemaRequestDetails
}

data class SchemaRequestDetails(
    val schemaName: String,
    val users: List<SchemaUser>,
    val engine: DatabaseEngine,
    val affiliation: String,
    val databaseInstance: DatabaseInstance
)

data class SchemaRequestPayload(
    val labels: Map<String, String>,
    val users: List<SchemaUser>,
    val engine: DatabaseEngine,
    val instanceLabels: Map<String, String>,
    val instanceName: String? = null,
    val instanceFallback: Boolean = false
)

data class SchemaUser(
    val name: String,
    val role: String,
    val affiliation: String
)

enum class DatabaseEngine {
    POSTGRES, ORACLE
}

data class SchemaIdRequest(
    val id: String,
    override val details: SchemaRequestDetails
) : SchemaProvisionRequest()

data class SchemaForAppRequest(
    val environment: String,
    val application: String,
    val generate: Boolean,
    override val details: SchemaRequestDetails
) : SchemaProvisionRequest()

data class SchemaProvisionResult(
    val request: SchemaProvisionRequest,
    val dbhSchema: DbhSchema,
    val responseText: String
)

data class SchemaProvisionResults(val results: List<SchemaProvisionResult>)

data class DbhUser(val username: String, val password: String, val type: String)

data class DatabaseSchemaInstance(val port: Long, val host: String?)

data class DbhSchema(
    val id: String,
    val type: String,
    val databaseInstance: DatabaseSchemaInstance,
    val jdbcUrl: String,
    val labels: Map<String, String> = mapOf(),
    private val users: List<DbhUser> = listOf()
) {
    val name: String
        get() = labels.get("name")!!

    val affiliation: String
        get() = labels.get("affiliation")!!

    // TOOD: support multiple users with different roles
    val username: String
        get() = users.firstOrNull()?.username ?: ""

    val password: String
        get() = users.firstOrNull()?.password ?: ""

    val userType: String
        get() = users.firstOrNull()?.type ?: ""

    val service: String
        get() = jdbcUrl.split("/").last()
}

@Service
@ConditionalOnProperty("boober.dbh")
class DatabaseSchemaProvisioner(
    @TargetService(ServiceTypes.AURORA)
    val restTemplate: RestTemplate,
    val mapper: ObjectMapper,
    val userDetailsProvider: UserDetailsProvider,
    @Value("\${boober.dbh}") val dbhUrl: String
) {
    val logger by logger()

    fun provisionSchemas(schemaProvisionRequests: List<SchemaProvisionRequest>): SchemaProvisionResults {

        if (schemaProvisionRequests.isEmpty()) throw IllegalArgumentException("SchemaProvisionRequest cannot be empty")

        val results: List<SchemaProvisionResult> = schemaProvisionRequests.map(this::provisionSchema)
        return SchemaProvisionResults(results)
    }

    fun provisionSchema(it: SchemaProvisionRequest): SchemaProvisionResult = when (it) {
        is SchemaIdRequest -> provisionFromId(it)
        is SchemaForAppRequest -> provisionForApplication(it)
    }

    private fun provisionFromId(request: SchemaIdRequest): SchemaProvisionResult {

        val (dbhSchema, responseText) = findSchemaById(request.id, request.details)
        return SchemaProvisionResult(request, dbhSchema, responseText)
    }

    fun findSchemaById(
        id: String,
        details: SchemaRequestDetails
    ): Pair<DbhSchema, String> {

        val roleString = details.users.joinToString(",") { it.name }
        val response: ResponseEntity<JsonNode> = try {
            restTemplate.getForEntity(
                "$dbhUrl/api/v1/schema/{1}?affiliation={2}&roles={3}",
                JsonNode::class.java,
                id,
                details.affiliation,
                roleString
            )
        } catch (e: Exception) {
            throw createProvisioningException("Unable to get information on schema with id $id", e)
        }

        return parseResponseFailIfEmpty(response)
    }

    private fun provisionForApplication(request: SchemaForAppRequest): SchemaProvisionResult {

        val user = userDetailsProvider.getAuthenticatedUser()
        val labels = mapOf(
            "affiliation" to request.details.affiliation,
            "environment" to "${request.details.affiliation}-${request.environment}",
            "application" to request.application,
            "name" to request.details.schemaName,
            "userId" to user.username
        )

        val (dbhSchema, responseText) = try {
            val find = findSchemaByLabels(labels, request.details)
            if (find == null && !request.generate) {
                throw ProvisioningException("Could not find schema with labels=$labels, generate disabled.")
            }
            find ?: createSchema(labels, request.details)
        } catch (e: Exception) {
            val message = e.message.orEmpty() + " Schema query: ${toLabelsString(labels)}"
            throw ProvisioningException(message, e)
        }

        return SchemaProvisionResult(request, dbhSchema, responseText)
    }

    private fun findSchemaByLabels(
        labels: Map<String, String>,
        details: SchemaRequestDetails
    ): Pair<DbhSchema, String>? {

        val labelsString = toLabelsString(labels)
        val roleString = details.users.joinToString(",") { it.name }
        val response: ResponseEntity<JsonNode> = try {
            restTemplate.getForEntity(
                "$dbhUrl/api/v1/schema/?labels={1}&roles={2}&engine={3}", JsonNode::class.java,
                labelsString, roleString, details.engine
            )
        } catch (e: Exception) {
            throw createProvisioningException("Unable to get database schema.", e)
        }

        return parseResponse(response)
    }

    private fun createSchema(
        labels: Map<String, String>,
        details: SchemaRequestDetails
    ): Pair<DbhSchema, String> {

        val payload =
            SchemaRequestPayload(
                users = details.users,
                engine = details.engine,
                instanceName = details.databaseInstance.name,
                instanceFallback = details.databaseInstance.fallback,
                instanceLabels = details.databaseInstance.labels,
                labels = labels
            )

        val response: ResponseEntity<JsonNode> = try {
            restTemplate.postForEntity("$dbhUrl/api/v1/schema/", payload, JsonNode::class.java)
        } catch (e: Exception) {
            throw createProvisioningException("Unable to create database schema.", e)
        }

        return parseResponseFailIfEmpty(response)
    }

    private fun parseResponseFailIfEmpty(response: ResponseEntity<JsonNode>): Pair<DbhSchema, String> {
        return parseResponse(response) ?: throw ProvisioningException("Expected dbh response to contain schema info.")
    }

    private fun parseResponse(response: ResponseEntity<JsonNode>): Pair<DbhSchema, String>? {

        val responseBody = response.body.toString()
        val dbApiEnvelope: DbApiEnvelope = mapper.readValue(responseBody, DbApiEnvelope::class.java)
        val size = dbApiEnvelope.items.size
        return when (size) {
            0 -> null
            1 -> Pair(dbApiEnvelope.items[0], responseBody)
            else -> throw ProvisioningException("Matched $size database schemas, should be exactly one.")
        }
    }

    private fun createProvisioningException(message: String, e: Exception): ProvisioningException {
        fun parseErrorResponse(responseMessage: String?): DbhErrorResponse {
            return try {
                mapper.readValue(responseMessage, DbhErrorResponse::class.java)
            } catch (e: Exception) {
                DbhErrorResponse("", emptyList(), 0)
            }
        }
        return when (e) {
            is HttpClientErrorException -> {
                val dbhErrorResponse = parseErrorResponse(e.responseBodyAsString)
                val errorMessage = if (dbhErrorResponse.items.isNotEmpty()) {
                    dbhErrorResponse.items.first()
                } else {
                    ""
                }
                ProvisioningException("$message cause=$errorMessage status=${dbhErrorResponse.status}", e)
            }
            else -> ProvisioningException(message, e)
        }
    }

    private fun toLabelsString(labels: Map<String, String>) = labels.filterKeys { it != "userId" }
        .map { "${it.key}=${it.value}" }
        .joinToString(",")

    data class DbApiEnvelope(val status: String, val items: List<DbhSchema> = listOf())

    data class DbhErrorResponse(val status: String, val items: List<String>, val totalCount: Int)
}
