package no.skatteetaten.aurora.boober.service.resourceprovisioning

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.ServiceTypes
import no.skatteetaten.aurora.boober.TargetService
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.feature.DatabaseInstance
import no.skatteetaten.aurora.boober.service.ProvisioningException
import no.skatteetaten.aurora.boober.utils.RetryingRestTemplateWrapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.RestTemplate

private val logger = KotlinLogging.logger {}

sealed class SchemaProvisionRequest {
    abstract val details: SchemaRequestDetails
    abstract val tryReuse: Boolean
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
    val user: User,
    override val details: SchemaRequestDetails,
    override val tryReuse: Boolean
) : SchemaProvisionRequest()

data class SchemaProvisionResults(val results: List<SchemaProvisionResult>)
data class SchemaProvisionResult(
    val request: SchemaProvisionRequest,
    val dbhSchema: DbhSchema
)

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
    val mapper: ObjectMapper
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

    fun provisionSchema(request: SchemaProvisionRequest): SchemaProvisionResult {
        val dbhSchema = when (request) {
            is SchemaIdRequest -> findSchemaById(request)
            is SchemaForAppRequest -> provisionForApplication(request)
        }

        return SchemaProvisionResult(request, dbhSchema)
    }

    private fun provisionForApplication(request: SchemaForAppRequest): DbhSchema {
        val labels = request.toLabels()
        val schemaFound =
            findSchemaByLabels(labels, request.details.engine) ?: tryReuseSchemaIfConfiguredElseNull(request)

        if (schemaFound == null && !request.generate) {
            throw ProvisioningException("Could not find schema with labels=$labels, generate disabled.")
        }

        return schemaFound ?: createSchema(labels, request.details)
    }

    private fun findSchemaInCooldown(forApp: Boolean = false, uriVar: Any): RestorableSchema? {
        val appPathElseEmpty = if (forApp) "?labels=" else ""
        return runCatching {
            restTemplate.get(
                JsonNode::class,
                "/api/v1/restorableSchema/$appPathElseEmpty{1}",
                uriVar
            )
        }.getRestorableSchema()
    }

    fun findSchemaById(
        request: SchemaIdRequest
    ): DbhSchema {
        val response: ResponseEntity<JsonNode> = try {
            restTemplate.get(
                JsonNode::class,
                "/api/v1/schema/{1}",
                request.id
            )
        } catch (e: Exception) {
            throw createProvisioningException(
                "Unable to get database schema with id=${request.id}",
                e
            )
        }

        return parseAsSingle(response)
            ?: tryReuseSchemaIfConfiguredElseNull(request)
            ?: throw ProvisioningException("Expected dbh response to contain schema info.")
    }

    private fun activateSchema(id: String): DbhSchema {
        return runCatching {
            restTemplate.patch(
                RestoreDatabaseSchemaPayload(active = true),
                JsonNode::class,
                "/api/v1/restorableSchema/{1}",
                id
            )
        }.onFailure(::reThrowError)
            .getOrElse {
                throw createProvisioningException("Unable to reactivate schema with id=$id.", it)
            }.parseAndFailIfEmpty()
    }

    private fun findSchemaByLabels(
        labels: Map<String, String>,
        engine: DatabaseEngine
    ): DbhSchema? {

        val labelsString = toLabelsString(labels)
        val response: ResponseEntity<JsonNode> = try {
            restTemplate.get(
                JsonNode::class,
                "/api/v1/schema/?labels={1}&engine={2}",
                labelsString,
                engine
            )
        } catch (e: Exception) {
            throw createProvisioningException("Unable to get database schema.", e)
        }

        return parseAsSingle(response)
    }

    private fun createSchema(
        labels: Map<String, String>,
        details: SchemaRequestDetails
    ): DbhSchema {

        val payload =
            SchemaRequestPayload(
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
            throw createProvisioningException("Unable to create database schema.", e)
        }

        return response.parseAndFailIfEmpty()
    }

    private fun tryReuseSchemaIfConfiguredElseNull(request: SchemaProvisionRequest): DbhSchema? {
        if (!request.tryReuse) return null

        val cooldownSchema =
            when (request) {
                is SchemaForAppRequest -> findSchemaInCooldown(
                    forApp = true,
                    uriVar = toLabelsString(request.toLabels())
                )
                is SchemaIdRequest -> findSchemaInCooldown(forApp = false, uriVar = request.id)
            }

        cooldownSchema ?: return null

        return cooldownSchema.databaseSchema
            .also { activateSchema(it.id) }
    }

    private inline fun <reified T : Any> ResponseEntity<JsonNode>.parseAndFailIfEmpty(): T =
        parseAsSingle<T>(this) ?: throw ProvisioningException("Expected dbh response to contain schema info.")

    private inline fun <reified T : Any> parse(response: ResponseEntity<JsonNode>): List<T> {
        return response.body?.let {
            mapper.convertValue<DbApiEnvelope<T>>(it)
        }?.items?.map {
            // TODO:This is not optimal, the convertValue above should deserialize completely, but trouble with type erasure.
            // Fix when refactor to use herkimer for database
            mapper.convertValue<T>(it)
        } ?: emptyList()
    }

    private inline fun <reified T : Any> parseAsSingle(response: ResponseEntity<JsonNode>): T? {
        val items = parse<T>(response)
        return when (val size = items.size) {
            0 -> null
            1 -> items.first()
            else -> throw ProvisioningException("Matched $size database schemas, should be exactly one.")
        }
    }

    private fun createProvisioningException(message: String, e: Throwable): ProvisioningException {

        fun parseErrorResponse(responseMessage: String): DbhErrorResponse? =
            runCatching {
                mapper.readValue<DbhErrorResponse>(responseMessage)
            }.onFailure(::reThrowError)
                .getOrNull()

        return when (e) {
            is RestClientResponseException -> {
                val dbhErrorResponse = parseErrorResponse(e.responseBodyAsString)
                val errorMessage = dbhErrorResponse?.items?.firstOrNull() ?: ""

                ProvisioningException("$message cause=$errorMessage status=${dbhErrorResponse?.status ?: ""}", e)
            }
            else -> ProvisioningException(message, e)
        }
    }

    private fun Result<ResponseEntity<JsonNode>>.getRestorableSchema() =
        this.onFailure(::reThrowError)
            .getOrNull()
            ?.let {
                parse<RestorableSchema>(it)
            }
            ?.sortedByDescending { it.setToCooldownAt }
            ?.firstOrNull()

    private fun SchemaForAppRequest.toLabels() =
        mapOf(
            "affiliation" to details.affiliation,
            "environment" to "${details.affiliation}-$environment",
            "application" to application,
            "name" to details.schemaName,
            "userId" to user.username
        )

    private fun toLabelsString(labels: Map<String, String>) = labels.filterKeys { it != "userId" }
        .map { "${it.key}=${it.value}" }
        .joinToString(",")

    private fun reThrowError(throwable: Throwable) {
        if (throwable is Error) throw throwable
    }
}

data class SchemaRequestDetails(
    val schemaName: String,
    val engine: DatabaseEngine,
    val affiliation: String,
    val databaseInstance: DatabaseInstance
)

data class RestoreDatabaseSchemaPayload(
    val active: Boolean
)

data class SchemaRequestPayload(
    val labels: Map<String, String>,
    val engine: DatabaseEngine,
    val instanceLabels: Map<String, String>,
    val instanceName: String? = null,
    val instanceFallback: Boolean = false
)

enum class DatabaseEngine {
    POSTGRES, ORACLE
}

data class DbhUser(val username: String, val password: String, val type: String)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DatabaseSchemaInstance(val port: Long, val host: String?)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DbApiEnvelope<T>(val status: String, val items: List<T> = emptyList())

data class DbhErrorResponse(val status: String, val items: List<String>, val totalCount: Int)

data class RestorableSchema(
    val setToCooldownAt: Long,
    val deleteAfter: Long,
    val databaseSchema: DbhSchema
)
