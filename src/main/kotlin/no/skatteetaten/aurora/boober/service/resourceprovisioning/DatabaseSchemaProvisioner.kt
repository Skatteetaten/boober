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
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.RestTemplate

private val logger = KotlinLogging.logger {}

private const val basePath: String = "/api/v1"
private const val schemaPath: String = "$basePath/schema"
private const val restorableSchemaPath: String = "$basePath/restorableSchema"

sealed class SchemaProvisionRequest {
    abstract val details: SchemaRequestDetails
    abstract val tryReuse: Boolean
    abstract val endPath: String
    abstract val uriVars: Array<out Any>
}

data class SchemaIdRequest(
    val id: String,
    override val details: SchemaRequestDetails,
    override val tryReuse: Boolean
) : SchemaProvisionRequest() {

    override val endPath = "{1}"
    override val uriVars = arrayOf(id)
}

data class SchemaForAppRequest(
    val environment: String,
    val application: String,
    val generate: Boolean,
    val ignoreMissingSchema: Boolean,
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

    val labelsAsString = labels.filter { it.key != "userId" }
        .map { "${it.key}=${it.value}" }
        .joinToString(",")

    override val endPath = "?labels={1}&engine={2}"
    override val uriVars = arrayOf(labelsAsString, details.engine)
}

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
        get() = labels["name"]!!

    val affiliation: String
        get() = labels["affiliation"]!!

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

    fun provisionSchema(request: SchemaProvisionRequest): DbhSchema? {
        val schema = findSchema(request) ?: findCooldownSchemaIfTryReuseEnabled(request)?.activate()

        if (schema != null) return schema

        return when (request) {
            is SchemaForAppRequest -> {
                if (request.generate) createSchema(request)
                else if (request.ignoreMissingSchema) null
                else throw ProvisioningException("Could not find schema with ${request.labelsAsString} generate disabled.")
            }
            is SchemaIdRequest -> throw ProvisioningException("Expected dbh response to contain schema info.")
        }
    }

    fun findSchema(
        request: SchemaProvisionRequest
    ): DbhSchema? {
        return runCatching {
            restTemplate.get(JsonNode::class, "$schemaPath/${request.endPath}", *request.uriVars)
        }.onFailure(::reThrowError)
            .getOrElse {
                if (it is HttpClientErrorException && it.statusCode == HttpStatus.NOT_FOUND) {
                    return null
                }
                val uri = "$schemaPath/${restTemplate.uri(request.endPath, *request.uriVars)}"

                throw createProvisioningException("Unable to get database schema with uri=$uri", it)
            }.parseAsSingle()
    }

    fun findCooldownSchemaIfTryReuseEnabled(request: SchemaProvisionRequest): DbhSchema? {
        if (!request.tryReuse) return null

        return request.run {
            runCatching {
                restTemplate.get(
                    JsonNode::class,
                    "$restorableSchemaPath/$endPath",
                    *uriVars
                )
            }.onFailure(::reThrowError)
                .getOrNull()
                ?.parse<RestorableSchema>()
                ?.firstOrNull()
                ?.databaseSchema
        }
    }

    fun createSchema(
        request: SchemaForAppRequest
    ): DbhSchema {
        return runCatching {
            restTemplate.post(
                url = "$schemaPath/",
                body = request.toSchemaRequest(),
                type = JsonNode::class
            )
        }.onFailure(::reThrowError)
            .getOrElse {
                throw createProvisioningException("Unable to create database schema.", it)
            }.parseAndFailIfEmpty()
    }

    fun DbhSchema.activate(): DbhSchema {
        return runCatching {
            restTemplate.patch(
                RestoreDatabaseSchemaPayload(active = true),
                JsonNode::class,
                "$restorableSchemaPath/{1}",
                id
            )
        }.onFailure(::reThrowError)
            .getOrElse {
                throw createProvisioningException("Unable to reactivate schema with id=$id.", it)
            }.parseAndFailIfEmpty()
    }

    private fun SchemaForAppRequest.toSchemaRequest() =
        this.details.let { details ->
            SchemaRequestPayload(
                engine = details.engine,
                instanceName = details.databaseInstance.name,
                instanceFallback = details.databaseInstance.fallback,
                instanceLabels = details.databaseInstance.labels,
                labels = labels
            )
        }

    private inline fun <reified T : Any> ResponseEntity<JsonNode>.parseAndFailIfEmpty(): T =
        this.parseAsSingle<T>() ?: throw ProvisioningException("Expected dbh response to contain schema info.")

    private inline fun <reified T : Any> ResponseEntity<JsonNode>.parse(): List<T> {
        return this.body?.let {
            mapper.convertValue<DbApiEnvelope<T>>(it)
        }?.items?.map {
            // TODO:This is not optimal, the convertValue above should deserialize completely, but trouble with type erasure.
            // Fix when refactor to use herkimer for database
            mapper.convertValue<T>(it)
        } ?: emptyList()
    }

    private inline fun <reified T : Any> ResponseEntity<JsonNode>.parseAsSingle(): T? {
        val items = this.parse<T>()
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

    private fun reThrowError(throwable: Throwable) {
        if (throwable is Error) {
            logger.error { throwable }
            throw throwable
        }
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
