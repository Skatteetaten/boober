package no.skatteetaten.aurora.boober.service.resourceprovisioning

import no.skatteetaten.aurora.boober.ServiceTypes
import no.skatteetaten.aurora.boober.TargetService
import no.skatteetaten.aurora.boober.service.ProvisioningException
import org.apache.commons.codec.digest.DigestUtils
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate

data class S3ProvisioningRequest(
    val affiliation: String,
    val environment: String,
    val application: String
) {
    fun toS3Handle(): String =
        DigestUtils.sha1Hex(listOf(affiliation, environment, application).joinToString("/"))
}

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
    val user: String,
    val path: String
)

private data class FionaCreateUserResponse(
    val secretKey: String,
    val serviceEndpoint: String,
    val bucket: String,
    val bucketRegion: String
)

@Service
class S3Provisioner(
    @TargetService(ServiceTypes.AURORA)
    val restTemplate: RestTemplate,
    @Value("\${integrations.fiona.url}") val fionaBaseUrl: String
) {
    fun provision(request: S3ProvisioningRequest): S3ProvisioningResult {
        val s3Handle = request.toS3Handle()
        val user = s3Handle
        val objectPrefix = s3Handle
        val response = try {
            restTemplate.postForObject(
                "$fionaBaseUrl/createuser",
                FionaCreateUserRequest(user, objectPrefix),
                FionaCreateUserResponse::class.java
            )
        } catch (e: Exception) {
            throw ProvisioningException("Error while provisioning S3 storage", e)
        } ?: throw ProvisioningException("Fiona unexpectedly returned an empty response")
        return S3ProvisioningResult(
            request,
            response.serviceEndpoint,
            user,
            response.secretKey,
            response.bucket,
            objectPrefix,
            response.bucketRegion
        )
    }
}


