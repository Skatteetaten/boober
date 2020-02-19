package no.skatteetaten.aurora.boober.service.resourceprovisioning

import org.apache.commons.codec.digest.DigestUtils
import org.springframework.stereotype.Service

data class S3ProvisioningRequest(
    val affiliation: String,
    val environment: String,
    val application: String
)

data class S3ProvisioningResult(
    val request: S3ProvisioningRequest,
    val serviceEndpoint: String,
    val accessKey: String,
    val secretKey: String,
    val bucketName: String,
    val objectPrefix: String
)

@Service
class S3Provisioner {
    fun provision(request: S3ProvisioningRequest): S3ProvisioningResult {
        return S3ProvisioningResult(
            request,
            "http://minio-aurora-dev.utv.paas.skead.no",
            "aurora",
            "fragleberget",
            "utv",
            request.toObjectPrefix()
        )
    }

    fun S3ProvisioningRequest.toObjectPrefix(): String =
        DigestUtils.sha1Hex(listOf(affiliation, environment, application).joinToString("/"))
}