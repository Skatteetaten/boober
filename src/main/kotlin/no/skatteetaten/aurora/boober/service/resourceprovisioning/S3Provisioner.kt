package no.skatteetaten.aurora.boober.service.resourceprovisioning

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import no.skatteetaten.aurora.boober.ServiceTypes
import no.skatteetaten.aurora.boober.TargetService
import no.skatteetaten.aurora.boober.service.ProvisioningException
import no.skatteetaten.aurora.boober.utils.RetryingRestTemplateWrapper

data class S3ProvisioningRequest(
    val bucketName: String,
    val path: String,
    val userName: String,
    val access: List<S3Access>
)

enum class S3Access {
    READ, WRITE, DELETE
}

data class S3ProvisioningResult(
    val serviceEndpoint: String,
    val accessKey: String,
    val secretKey: String
)

private data class FionaCreateUserAndPolicyPayload(
    val userName: String,
    val access: List<S3Access>
)

private data class FionaCreateUserAndPolicyResponse(
    val accessKey: String,
    val secretKey: String,
    val host: String
)

@Component
@ConditionalOnProperty("integrations.fiona.url")
class FionaRestTemplateWrapper(
    @TargetService(ServiceTypes.AURORA) restTemplate: RestTemplate,
    @Value("\${integrations.fiona.url}") override val baseUrl: String,
    @Value("\${integrations.fiona.retries:3}") override val retries: Int
) : RetryingRestTemplateWrapper(restTemplate = restTemplate, retries = retries, baseUrl = baseUrl)

@Service
@ConditionalOnProperty(value = ["integrations.s3.variant"], havingValue = "minio", matchIfMissing = false)
class S3Provisioner(
    val restTemplate: FionaRestTemplateWrapper
) {
    fun provision(request: S3ProvisioningRequest): S3ProvisioningResult {
        val response = try {
            request.run {
                restTemplate.post(
                    url = "/buckets/$bucketName/paths/$path/userpolicies/",
                    body = FionaCreateUserAndPolicyPayload(userName, access),
                    type = FionaCreateUserAndPolicyResponse::class
                )
            }.body ?: throw ProvisioningException("Fiona unexpectedly returned an empty response")
        } catch (e: Exception) {
            throw ProvisioningException("Error while provisioning S3 storage; ${e.message}", e)
        }

        return S3ProvisioningResult(
            serviceEndpoint = response.host,
            accessKey = response.accessKey,
            secretKey = response.secretKey
        )
    }
}
