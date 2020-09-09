package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.convertValue
import no.skatteetaten.aurora.boober.ServiceTypes
import no.skatteetaten.aurora.boober.TargetService
import no.skatteetaten.aurora.boober.configureDefaults
import no.skatteetaten.aurora.boober.feature.name
import no.skatteetaten.aurora.boober.utils.RetryingRestTemplateWrapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import java.time.LocalDateTime

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

data class ApplicationDeploymentPayload(
    val name: String,
    val environmentName: String,
    val cluster: String,
    val businessGroup: String,
    val applicationName: String
)

data class ApplicationDeploymentHerkimer(
    val id: String,
    val name: String,
    val environmentName: String,
    val cluster: String,
    val businessGroup: String,
    val applicationName: String,
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
class HerkimerRestTemplateWrapper(@TargetService(ServiceTypes.AURORA) restTemplate: RestTemplate) :
    RetryingRestTemplateWrapper(restTemplate)

@Service
class HerkimerService(
    val client: HerkimerRestTemplateWrapper,
    @Value("\${integrations.herkimer.url}") val herkimerUrl: String,
    private val objectMapper: ObjectMapper
) {
    private val herkimerObjectmapper = objectMapper.configureDefaults()
    fun createApplicationDeployment(adPayload: ApplicationDeploymentPayload): ApplicationDeploymentHerkimer {
        val response = client.post(
            body = adPayload,
            url = "$herkimerUrl/applicationDeployment",
            type = HerkimerResponse::class
        )
        val adHHerkimerJsonNode = response.body?.items?.single() ?: TODO("Find out which exception if no body or return null?")

        return herkimerObjectmapper.convertValue(adHHerkimerJsonNode)
    }

    fun getClaimedResources(adId: String, resourceKind: ResourceKind): List<ResourceHerkimer> {
        val claimedResourcesJsonNode = client.get(
            HerkimerResponse::class,
            "$herkimerUrl/resource?claimedBy=$adId"
        ).body?.items ?: TODO("Find out which exception or return null?")

        val claimedResources = herkimerObjectmapper.convertValue<List<ResourceHerkimer>>(claimedResourcesJsonNode)

        return claimedResources.filter { it.kind == resourceKind }
    }

    fun createResourceAndClaim(ownerId: String, resourceKind: ResourceKind, resourceName: String, credentials: Any) {
        val resourceResponse = client.post(
            type = HerkimerResponse::class,
            url = "$herkimerUrl/resource",
            body = ResourcePayload(
                name = resourceName,
                kind = resourceKind,
                ownerId = ownerId
            )
        ).body ?: TODO("")

        require(resourceResponse.success)

        val resourceId = herkimerObjectmapper.convertValue<ResourceClaimHerkimer>(resourceResponse.items.single()).id

        val claimResponse = client.post(
            type = HerkimerResponse::class,
            url = "$herkimerUrl/resource/$resourceId/claims",
            body = ResourceClaimPayload(
                ownerId,
                credentials
            )
        ).body ?: TODO()

        require(claimResponse.success)
    }
}
