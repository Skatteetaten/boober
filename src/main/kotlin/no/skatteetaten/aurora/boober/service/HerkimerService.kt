package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.ServiceTypes
import no.skatteetaten.aurora.boober.TargetService
import no.skatteetaten.aurora.boober.utils.RetryingRestTemplateWrapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}

data class HerkimerResponse<T : Any>(
    val success: Boolean = true,
    val message: String = "OK",
    val items: List<T> = emptyList(),
    val errors: List<ErrorResponse> = emptyList(),
    val count: Int = items.size + errors.size
)

data class ErrorResponse(val errorMessage: String)
data class ResourceClaimPayload(
    val ownerId: String,
    val credentials: Any
)

data class ApplicationDeploymentHerkimer(
    val id: String,
    val name: String,
    val environmentName: String,
    val cluster: String,
    val businessGroup: String,
    val createdDate: LocalDateTime,
    val modifiedDate: LocalDateTime,
    val createdBy: String,
    val modifiedBy: String
)

data class ResourceHerkimer(
    val id: String,
    val name: String,
    val kind: ResourceKind,
    val ownerId: String,
    val claims: List<ResourceClaimHerkimer>? = null,
    val createdDate: LocalDateTime,
    val modifiedDate: LocalDateTime,
    val createdBy: String,
    val modifiedBy: String
)

data class ResourceClaimHerkimer(
    val id: String,
    val ownerId: String,
    val resourceId: Long,
    val credentials: JsonNode,
    val createdDate: LocalDateTime,
    val modifiedDate: LocalDateTime,
    val createdBy: String,
    val modifiedBy: String
)

enum class ResourceKind {
    MinioPolicy, ManagedPostgresDatabase, ManagedOracleSchema, ExternalSchema
}

data class ResourcePayload(
    val name: String,
    val kind: ResourceKind,
    val ownerId: String
)

@Component
class HerkimerRestTemplateWrapper(
    @TargetService(ServiceTypes.AURORA) restTemplate: RestTemplate,
    @Value("\${integrations.herkimer.url}") override val baseUrl: String,
    @Value("\${integrations.herkimer.retries:3}") override val retries: Int
) : RetryingRestTemplateWrapper(restTemplate = restTemplate, retries = retries, baseUrl = baseUrl)

@Service
class HerkimerService(
    val client: HerkimerRestTemplateWrapper
) {
    fun createApplicationDeployment(adPayload: ApplicationDeploymentCreateRequest): ApplicationDeploymentHerkimer {
        val response = client.post(
            body = adPayload,
            url = "/applicationDeployment",
            type = HerkimerResponse::class
        )
        val herkimerResponse = response.getBodyOrThrow()

        if (!herkimerResponse.success) throw ProvisioningException("Unable to create ApplicationDeployment with payload=$adPayload, cause=${herkimerResponse.message}")

        return herkimerObjectMapper.convertValue(herkimerResponse.items.single())
    }

    fun getClaimedResources(
        claimOwnerId: String,
        resourceKind: ResourceKind,
        name: String? = null
    ): List<ResourceHerkimer> {
        val url = "/resource?claimedBy=$claimOwnerId&resourceKind=$resourceKind${
            if (name != null) "&name=$name" else ""
        }"

        val herkimerResponse = client.get(
            HerkimerResponse::class,
            url
        ).getBodyOrThrow()

        if (!herkimerResponse.success) throw ProvisioningException("Unable to get claimed resources. cause=${herkimerResponse.message}")

        return herkimerObjectMapper.convertValue(herkimerResponse.items)
    }

    fun createResourceAndClaim(ownerId: String, resourceKind: ResourceKind, resourceName: String, credentials: Any) {
        val resourceResponse = client.post(
            type = HerkimerResponse::class,
            url = "/resource",
            body = ResourcePayload(
                name = resourceName,
                kind = resourceKind,
                ownerId = ownerId
            )
        ).getBodyOrThrow()

        if (!resourceResponse.success) throw ProvisioningException("Unable to create resource of type=$resourceKind. cause=${resourceResponse.message}")

        val resourceId = herkimerObjectMapper.convertValue<ResourceHerkimer>(resourceResponse.items.single()).id

        val claimResponse = client.post(
            type = HerkimerResponse::class,
            url = "/resource/$resourceId/claims",
            body = ResourceClaimPayload(
                ownerId,
                credentials
            )
        ).getBodyOrThrow()

        if (!claimResponse.success) throw ProvisioningException("Unable to create claim for resource with id=$resourceId and ownerId=$ownerId. cause=${claimResponse.message}")
    }
}

private fun <T> ResponseEntity<T>.getBodyOrThrow() =
    this.body ?: throw EmptyBodyException("Fatal error happened. Received empty body from Herkimer").also {
        logger.error(it) { "Null body happened in caller method=${it.stackTrace[2]} statusCode=${this.statusCode}" }
    }

internal val herkimerObjectMapper: ObjectMapper = jacksonObjectMapper()
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    .disable(SerializationFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS)
    .enable(SerializationFeature.WRITE_DATES_WITH_ZONE_ID)
    .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
    .registerModule(Jdk8Module())
    .registerModule(JavaTimeModule())
    .registerModule(ParameterNamesModule())
