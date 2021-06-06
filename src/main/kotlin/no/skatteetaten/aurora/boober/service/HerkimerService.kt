package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.LocalDateTime
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule
import no.skatteetaten.aurora.boober.ServiceTypes
import no.skatteetaten.aurora.boober.TargetService
import no.skatteetaten.aurora.boober.utils.RetryingRestTemplateWrapper

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
    val name: String,
    val credentials: Any
)

data class ApplicationDeploymentCreateRequest(
    val name: String,
    val environmentName: String,
    val cluster: String,
    val businessGroup: String
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

@JsonIgnoreProperties(ignoreUnknown = true)
data class ResourceHerkimer(
    val id: String,
    val name: String,
    val kind: ResourceKind,
    val ownerId: String,
    val claims: List<ResourceClaimHerkimer> = emptyList(),
    val parentId: String?,
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
    val name: String,
    val createdDate: LocalDateTime,
    val modifiedDate: LocalDateTime,
    val createdBy: String,
    val modifiedBy: String
)

enum class ResourceKind {
    MinioPolicy, MinioObjectArea, ManagedPostgresDatabase, ManagedOracleSchema, ExternalSchema, PostgresDatabaseInstance, OracleDatabaseInstance, StorageGridTenant, StorageGridObjectArea
}

data class ResourcePayload(
    val name: String,
    val kind: ResourceKind,
    val ownerId: String,
    val parentId: String?
)

@ConditionalOnProperty("integrations.herkimer.url")
@ConfigurationProperties(prefix = "integrations.herkimer")
@ConstructorBinding
data class HerkimerConfiguration(val url: String, val fallback: Map<String, String> = emptyMap(), val retries: Int = 3)

@Component
@ConditionalOnProperty("integrations.herkimer.url")
class HerkimerRestTemplateWrapper(
    @TargetService(ServiceTypes.AURORA) restTemplate: RestTemplate,
    val configuration: HerkimerConfiguration
) : RetryingRestTemplateWrapper(
    restTemplate = restTemplate,
    retries = configuration.retries,
    baseUrl = configuration.url
)

@Service
@ConditionalOnProperty("integrations.herkimer.url")
class HerkimerService(
    val client: HerkimerRestTemplateWrapper
) {
    fun createApplicationDeployment(adPayload: ApplicationDeploymentCreateRequest): ApplicationDeploymentHerkimer {
        val response = client.post(
            body = adPayload,
            url = "/applicationDeployment",
            type = HerkimerResponse::class
        )
        val herkimerResponse = response.body

        if (herkimerResponse?.success != true) throw ProvisioningException("Unable to create ApplicationDeployment with payload=$adPayload, cause=${herkimerResponse.messageOrDefault}")

        return objectMapperWithTime.convertValue(herkimerResponse.items.single())
    }

    private fun getResourceUrl(claimOwnerId: String?, resourceKind: ResourceKind, name: String?): String {
        val nameParam = name?.let { "&name=$it" }.orEmpty()
        val claimOwnerIdParam = claimOwnerId?.let { "claimedBy=$it&" } ?: "onlyMyClaims=false&"
        return "/resource?${claimOwnerIdParam}resourceKind=$resourceKind$nameParam"
    }

    fun getClaimedResources(
        claimOwnerId: String? = null,
        resourceKind: ResourceKind,
        name: String? = null
    ): List<ResourceHerkimer> {
        val herkimerResponse = client.get(
            HerkimerResponse::class,
            getResourceUrl(claimOwnerId, resourceKind, name)
        ).body

        if (herkimerResponse?.success != true) throw ProvisioningException("Unable to get claimed resources. cause=${herkimerResponse.messageOrDefault}")

        return objectMapperWithTime.convertValue(herkimerResponse.items)
    }

    fun createResourceAndClaim(
        ownerId: String,
        resourceKind: ResourceKind,
        resourceName: String,
        claimName: String,
        credentials: Any,
        parentId: String? = null
    ) {
        val resourceResponse = client.post(
            type = HerkimerResponse::class,
            url = "/resource",
            body = ResourcePayload(
                name = resourceName,
                kind = resourceKind,
                ownerId = ownerId,
                parentId = parentId
            )
        ).body

        if (resourceResponse?.success != true) throw ProvisioningException("Unable to create resource of type=$resourceKind. cause=${resourceResponse.messageOrDefault}")

        val resourceId = objectMapperWithTime.convertValue<ResourceHerkimer>(resourceResponse.items.single()).id

        val claimResponse = client.post(
            type = HerkimerResponse::class,
            url = "/resource/$resourceId/claims",
            body = ResourceClaimPayload(
                ownerId = ownerId,
                name = claimName,
                credentials = credentials
            )
        ).body

        if (claimResponse?.success != true) throw ProvisioningException("Unable to create claim for resource with id=$resourceId and ownerId=$ownerId. cause=${claimResponse.messageOrDefault}")
    }
}

internal val objectMapperWithTime: ObjectMapper = jacksonObjectMapper()
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    .disable(SerializationFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS)
    .enable(SerializationFeature.WRITE_DATES_WITH_ZONE_ID)
    .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
    .registerModule(Jdk8Module())
    .registerModule(JavaTimeModule())
    .registerModule(ParameterNamesModule())

private val HerkimerResponse<*>?.messageOrDefault: String
    get() = this?.message ?: "empty body"
