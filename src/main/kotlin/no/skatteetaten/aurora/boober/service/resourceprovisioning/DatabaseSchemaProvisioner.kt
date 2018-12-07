package no.skatteetaten.aurora.boober.service.resourceprovisioning

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import no.skatteetaten.aurora.boober.ServiceTypes
import no.skatteetaten.aurora.boober.TargetService
import no.skatteetaten.aurora.boober.mapper.v1.DatabaseFlavor
import no.skatteetaten.aurora.boober.mapper.v1.DatabasePermission
import no.skatteetaten.aurora.boober.service.ProvisioningException
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.utils.logger
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate

sealed class SchemaProvisionRequest {

    abstract val details: SchemaRequestDetails
}

data class SchemaRequestDetails(
    val schemaName: String,
    val parameters: Map<String, String>,
    // Hvis roles er tom fyll inn SCHAMA med ALL
    val roles: Map<String, DatabasePermission>,
    val exposeTo: Map<String, String>,
    val flavor: DatabaseFlavor,
    val affiliation: String
)

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

data class DatabaseInstance(val port: Long, val host: String?)

data class DbhSchema(
    val id: String,
    val type: String,
    val databaseInstance: DatabaseInstance,
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
class DatabaseSchemaProvisioner(
    // TODO: Rename to DBH and put dbhUrl as baseUrl in client?
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

        // TODO: BAS?
        val roleString = details.roles.keys.joinToString(",")
        val response: ResponseEntity<JsonNode> = try {
            restTemplate.getForEntity(
                "{0}/api/v1/schema/{1}?affiliation={2}&roles={3}",
                JsonNode::class.java,
                dbhUrl,
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
            // TODO should we really hard code this here? Why not just send in environment here?
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

        // TODO: BAS?
        val labelsString = toLabelsString(labels)
        val roleString = details.roles.keys.joinToString(",")
        val response: ResponseEntity<JsonNode> = try {
            restTemplate.getForEntity(
                "{0}/api/v1/schema/?labels={1}&roles={2}", JsonNode::class.java, dbhUrl,
                labelsString, roleString
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

        // TODO: BAS?
        val payload = mapOf(
            "labels" to labels,
            "roles" to details.roles,
            "flavor" to details.flavor,
            "exposeTo" to details.exposeTo,
            "parameters" to details.parameters
        )
        val response: ResponseEntity<JsonNode> = try {
            restTemplate.postForEntity("{0}/api/v1/schema/", payload, JsonNode::class.java, dbhUrl)
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
        fun parseErrorResponse(responseMessage: String?): String {
            return try {
                val dbhResponse = mapper.readValue(responseMessage, DbhErrorResponse::class.java)
                dbhResponse.errorMessage
            } catch (e: Exception) {
                ""
            }
        }
        return when (e) {
            is HttpClientErrorException -> {
                val dbhErrorResponse = parseErrorResponse(e.responseBodyAsString)
                ProvisioningException("$message cause=$dbhErrorResponse", e)
            }
            else -> ProvisioningException(message, e)
        }
    }

    private fun toLabelsString(labels: Map<String, String>) = labels.filterKeys { it != "userId" }
        .map { "${it.key}=${it.value}" }
        .joinToString(",")

    data class DbApiEnvelope(val status: String, val items: List<DbhSchema> = listOf())

    data class DbhErrorResponse(val errorMessage: String)
}
