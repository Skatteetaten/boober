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
    abstract val findPath: String
    abstract val findVars: Array<out Any>
}
data class SchemaIdRequest(
    val id: String,
    override val details: SchemaRequestDetails,
    override val tryReuse: Boolean
) : SchemaProvisionRequest() {

    override val findPath = "/api/v1/schema/{1}"
    override val findVars = arrayOf(id)
}

data class SchemaForAppRequest(
    val environment: String,
    val application: String,
    val generate: Boolean,
    val user: User,
    override val details: SchemaRequestDetails,
    override val tryReuse: Boolean
) : SchemaProvisionRequest() {

    val labels =
        mapOf(
            "affiliation" to details.affiliation,
            "environment" to "${details.affiliation}-$environment",
            "application" to application,
            "name" to details.schemaName,
            "userId" to user.username
        )
    override val findPath = "/api/v1/schema/?labels={1}&engine={2}"
    override val findVars = arrayOf(labels.toLabelsString(), details.engine)
}

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

    fun provisionSchemas(schemaProvisionRequests: List<SchemaProvisionRequest>): SchemaProvisionResults {

        if (schemaProvisionRequests.isEmpty()) throw IllegalArgumentException("SchemaProvisionRequest cannot be empty")

        val results: List<SchemaProvisionResult> = schemaProvisionRequests.map(this::provisionSchema)
        return SchemaProvisionResults(results)
    }

    // Ideally this should just return the schema, and not be used anywhere besides validate
    fun provisionSchema(request: SchemaProvisionRequest): SchemaProvisionResult {

        val schema = findSchema(request) ?: tryReuseSchemaIfConfiguredElseNull(request)

        if (schema != null) {
            return SchemaProvisionResult(request, schema)
        }

        return when (request) {
            is SchemaIdRequest -> throw ProvisioningException("Expected dbh response to contain schema info.")
            is SchemaForAppRequest -> {
                if (!request.generate) {
                    throw ProvisioningException("Could not find schema with ${request.labels.toLabelsString()} generate disabled.")
                } else {
                    SchemaProvisionResult(request, createSchema(request))
                }
            }
        }
    }

    fun findSchema(
        request: SchemaProvisionRequest
    ): DbhSchema? {
        return try {
            parseAsSingle(restTemplate.get(JsonNode::class, request.findPath, *request.findVars))
        } catch (e: Exception) {
            throw createProvisioningException("Unable to get database schema with uri=${restTemplate.uri(request.findPath, *request.findVars)}", e)
        }
    }

    private fun createSchema(
        request: SchemaForAppRequest
    ): DbhSchema {
        val details = request.details
        val payload =
            SchemaRequestPayload(
                engine = details.engine,
                instanceName = details.databaseInstance.name,
                instanceFallback = details.databaseInstance.fallback,
                instanceLabels = details.databaseInstance.labels,
                labels = request.labels
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

        // this could use the same pattern as i did in findSchema. since the vars are the same only the path need to change really.
        val cooldownSchema =
            when (request) {
                is SchemaForAppRequest -> findSchemaInCooldown(
                    forApp = true,
                    uriVar = request.labels.toLabelsString()
                )
                is SchemaIdRequest -> findSchemaInCooldown(forApp = false, uriVar = request.id)
            }

        cooldownSchema ?: return null

        return cooldownSchema.databaseSchema
            .also { activateSchema(it.id) }
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
            }?.maxBy { it.setToCooldownAt }

    private fun reThrowError(throwable: Throwable) {
        if (throwable is Error) throw throwable
    }
}

fun Map<String, String>.toLabelsString() = this.filterKeys { it != "userId" }
    .map { "${it.key}=${it.value}" }
    .joinToString(",")

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
