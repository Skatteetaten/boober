package no.skatteetaten.aurora.boober.service.resourceprovisioning

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.ServiceTypes
import no.skatteetaten.aurora.boober.TargetService
import no.skatteetaten.aurora.boober.service.ProvisioningException
import no.skatteetaten.aurora.boober.utils.RetryingRestTemplateWrapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate

data class S3ProvisioningRequest(
    val bucketName: String,
    val path: String,
    val adminCredentials: JsonNode,
    val userName: String,
    val access: S3Access
)

enum class S3Access {
    READ, WRITE
}

data class AdminCredentials(
    val secretKey: String,
    val accessKey: String
)

data class S3ProvisioningResult(
    val request: S3ProvisioningRequest,
    val serviceEndpoint: String,
    val accessKey: String,
    val secretKey: String,
    val bucketName: String,
    val objectPrefix: String,
    val bucketRegion: String
)

private data class FionaCreateUserRequest(
    val access: S3Access,
    val adminCredentials: AdminCredentials
)

private data class FionaCreateUserResponse(
    val accessKey: String,
    val secretKey: String,
    val serviceEndpoint: String,
    val bucket: String,
    val bucketRegion: String
)

@Component
@ConditionalOnProperty("integrations.fiona.url")
class FionaRestTemplateWrapper(
    @TargetService(ServiceTypes.AURORA) restTemplate: RestTemplate,
    @Value("\${integrations.fiona.url}") override val baseUrl: String,
    @Value("\${integrations.fiona.retries:3}") override val retries: Int
) : RetryingRestTemplateWrapper(restTemplate = restTemplate, retries = retries, baseUrl = baseUrl)

@Service
@ConditionalOnProperty("integrations.fiona.url")
class S3Provisioner(
    val restTemplate: FionaRestTemplateWrapper
) {
    fun provision(request: S3ProvisioningRequest): S3ProvisioningResult {
        val response = try {
            request.run {
                val adminCredentialsParsed = jacksonObjectMapper().convertValue<AdminCredentials>(request.adminCredentials)
                restTemplate.post(
                    url = "/bucket/$bucketName/path/$path/userPolicy/$userName",
                    body = FionaCreateUserRequest(access, adminCredentialsParsed),
                    type = FionaCreateUserResponse::class
                )
            }.body ?: throw ProvisioningException("Fiona unexpectedly returned an empty response")
        } catch (e: Exception) {
            throw ProvisioningException("Error while provisioning S3 storage; ${e.message}", e)
        }
        return S3ProvisioningResult(
            request = request,
            serviceEndpoint = response.serviceEndpoint,
            accessKey = response.accessKey,
            secretKey = response.secretKey,
            bucketName = response.bucket,
            objectPrefix = request.path,
            bucketRegion = response.bucketRegion
        )
    }
}
