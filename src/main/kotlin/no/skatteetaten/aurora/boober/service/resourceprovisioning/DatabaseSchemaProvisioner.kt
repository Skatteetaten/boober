package no.skatteetaten.aurora.boober.service.resourceprovisioning

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.ServiceTypes
import no.skatteetaten.aurora.boober.TargetService
import no.skatteetaten.aurora.boober.feature.DatabaseInstance
import no.skatteetaten.aurora.boober.service.ProvisioningException
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.utils.RetryingRestTemplateWrapper
import org.apache.commons.lang3.exception.ExceptionUtils
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.RestTemplate

private val logger = KotlinLogging.logger {}

sealed class SchemaProvisionRequest {

    abstract val details: SchemaRequestDetails
    abstract val tryReuse: Boolean
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
    override val details: SchemaRequestDetails,
    override val tryReuse: Boolean
) : SchemaProvisionRequest()

data class SchemaForAppRequest(
    val environment: String,
    val application: String,
    val generate: Boolean,
    override val details: SchemaRequestDetails,
    override val tryReuse: Boolean
) : SchemaProvisionRequest()

data class SchemaProvisionResult(
    val request: SchemaProvisionRequest,
    val dbhSchema: DbhSchema,
    val responseText: String
)

data class SchemaProvisionResults(val results: List<SchemaProvisionResult>)

data class DbhUser(val username: String, val password: String, val type: String)

data class DatabaseSchemaInstance(val port: Long, val host: String?)

@JsonIgnoreProperties(ignoreUnknown = true)
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

    val username: String
        get() = users.firstOrNull()?.username ?: ""

    val password: String
        get() = users.firstOrNull()?.password ?: ""

    val userType: String
        get() = users.firstOrNull()?.type ?: ""

    val service: String
        get() = jdbcUrl.split("/").last()
}

@Component
@ConditionalOnProperty("integrations.dbh.url")
class DbhRestTemplateWrapper(
    @TargetService(ServiceTypes.AURORA) restTemplate: RestTemplate,
    @Value("\${integrations.dbh.url}") override val baseUrl: String,
    @Value("\${integrations.dbh.retries:0}") override val retries: Int
) : RetryingRestTemplateWrapper(restTemplate = restTemplate, retries = retries, baseUrl = baseUrl)

@Service
@ConditionalOnProperty("integrations.dbh.url")
class DatabaseSchemaProvisioner(
    val restTemplate: DbhRestTemplateWrapper,
    val mapper: ObjectMapper,
    val userDetailsProvider: UserDetailsProvider
) {

    /*
      TODO  Error handling, right now provision schema is called in validation for id schemas and schemas with generate false
      This method will fail on the first error and not collect errors. Should we collect up the errors in a correct way?

      How do we pass state from one step of a feature to another? Because this is actually a use case for it.
     */
    fun provisionSchemas(schemaProvisionRequests: List<SchemaProvisionRequest>): SchemaProvisionResults {

        if (schemaProvisionRequests.isEmpty()) throw IllegalArgumentException("SchemaProvisionRequest cannot be empty")

        val results: List<SchemaProvisionResult> = schemaProvisionRequests.map(this::provisionSchema)
        return SchemaProvisionResults(results)
    }

    fun provisionSchema(it: SchemaProvisionRequest): SchemaProvisionResult = when (it) {
        is SchemaIdRequest -> provisionFromId(it)
        is SchemaForAppRequest -> provisionForApplication(it)
    }

    private fun findSchemaInCooldown(id: String): Pair<DbhSchema, String>? {
        return runCatching {
            restTemplate.get(
                JsonNode::class,
                "/api/v1/restorableSchema/{1}",
                id
            )
        }.onFailure(::reThrowError)
            .getOrNull()
            ?.parse()
    }

    private fun findSchemaInCooldown(labels: Map<String, String>): Pair<DbhSchema, String>? {
        return runCatching {
            restTemplate.get(
                JsonNode::class,
                "/restorableSchema?labels={1}",
                labels
            )
        }.getOrNull()
            ?.parse()
    }

    private fun reuseOrProvision(tryReuse: Boolean,id: String): Pair<DbhSchema, String> {
        if(tryReuse) {
            val schemaInCooldown = findSchemaInCooldown(id)

            if(schemaInCooldown != null) {
                return activateSchema(id)
            }
        }

        return findSchemaById(id)
    }
    private fun provisionFromId(request: SchemaIdRequest): SchemaProvisionResult {
        val (dbhSchema, responseText) = reuseOrProvision(request.tryReuse, request.id)

        return SchemaProvisionResult(request, dbhSchema, responseText)
    }

    fun findSchemaById(
        id: String
    ): Pair<DbhSchema, String> {
        val response: ResponseEntity<JsonNode> = try {
            restTemplate.get(
                JsonNode::class,
                "/api/v1/schema/{1}",
                id
            )
        } catch (e: Exception) {
            throw createProvisioningException(
                "Unable to get information on schema with id=$id",
                e
            )
        }

        return response.parseAndFailIfEmpty()
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

        val (dbhSchema, responseText) = reuseOrProvisionSchema(labels, request)

        return SchemaProvisionResult(request, dbhSchema, responseText)
    }

    data class RestoreDatabaseSchemaPayload(
        val active: Boolean
    )

    private fun activateSchema(id: String): Pair<DbhSchema, String> {
        return runCatching {
            restTemplate.patch(
                RestoreDatabaseSchemaPayload(active = true),
                JsonNode::class,
                "/restorableSchema/{1}",
                id
            )
        }.onFailure(::reThrowError)
            .getOrElse {
                throw createProvisioningException("Unable to reactivate schema with id=$id.", it)
            }.parseAndFailIfEmpty()
    }

    private fun reuseOrProvisionSchema(
        labels: Map<String, String>,
        request: SchemaForAppRequest
    ): Pair<DbhSchema, String> {
        return try {
            if (request.tryReuse) {
                val schemaInCooldown = findSchemaInCooldown(labels)

                if (schemaInCooldown != null) {
                    return activateSchema(schemaInCooldown.first.id)
                }
            }

            val schemaFound = findSchemaByLabels(labels, request.details.engine)

            if (schemaFound == null && !request.generate) {
                throw ProvisioningException("Could not find schema with labels=$labels, generate disabled.")
            }

            schemaFound ?: createSchema(labels, request.details)
        } catch (e: Exception) {
            val rootCauseMessage = ExceptionUtils.getRootCauseMessage(e)
            val message = "${e.message.orEmpty()} $rootCauseMessage  Schema query: ${toLabelsString(labels)}"
            throw ProvisioningException(message, e)
        }
    }

    private fun findSchemaByLabels(
        labels: Map<String, String>,
        engine: DatabaseEngine
    ): Pair<DbhSchema, String>? {

        val labelsString = toLabelsString(labels)
        val response: ResponseEntity<JsonNode> = try {
            restTemplate.get(
                JsonNode::class,
                "/api/v1/schema?labels={1}",
                labelsString,
                engine
            )
        } catch (e: Exception) {
            throw createProvisioningException("Unable to get database schema.", e)
        }

        return response.parse()
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
            restTemplate.post(
                url = "/api/v1/schema/",
                body = payload,
                type = JsonNode::class
            )
        } catch (e: Exception) {
            throw createProvisioningException("Unable to create database schema.${e.getDbhErrorMessage()}", e)
        }

        return response.parseAndFailIfEmpty()
    }

    private fun ResponseEntity<JsonNode>.parseAndFailIfEmpty(): Pair<DbhSchema, String> =
        parse() ?: throw ProvisioningException("Expected dbh response to contain schema info.")

    private fun ResponseEntity<JsonNode>.parse(): Pair<DbhSchema, String>? {
        val responseBody = this.body.toString()
        val dbApiEnvelope: DbApiEnvelope = mapper.readValue(responseBody, DbApiEnvelope::class.java)
        val size = dbApiEnvelope.items.size
        return when (size) {
            0 -> null
            1 -> Pair(dbApiEnvelope.items[0], responseBody)
            else -> throw ProvisioningException("Matched $size database schemas, should be exactly one.")
        }
    }

    private fun createProvisioningException(message: String, e: Throwable): ProvisioningException {
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class DbApiEnvelope(val status: String, val items: List<DbhSchema> = listOf())

    data class DbhErrorResponse(val status: String, val items: List<String>, val totalCount: Int)

    private fun reThrowError(throwable: Throwable) {
        if (throwable is Error) throw throwable
    }

    private fun Exception.getDbhErrorMessage() =
        if (this is RestClientResponseException) {
            this.runCatching {
                mapper.readValue<DbhErrorResponse>(this.responseBodyAsString)
                    .items
                    .firstOrNull()
                    ?.let { " $it." } ?: ""
            }.getOrElse { "" }
        } else ""
}
