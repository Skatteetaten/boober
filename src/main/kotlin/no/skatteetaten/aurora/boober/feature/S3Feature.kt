package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newEnvVar
import com.fkorotkov.kubernetes.newSecret
import com.fkorotkov.kubernetes.secretKeyRef
import com.fkorotkov.kubernetes.valueFrom
import io.fabric8.kubernetes.api.model.Secret
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.addEnvVar
import no.skatteetaten.aurora.boober.model.findSubKeysExpanded
import no.skatteetaten.aurora.boober.service.HerkimerService
import no.skatteetaten.aurora.boober.service.ResourceKind
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3Access
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3Provisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3ProvisioningRequest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3ProvisioningResult
import no.skatteetaten.aurora.boober.utils.ConditionalOnPropertyMissingOrEmpty
import no.skatteetaten.aurora.boober.utils.boolean
import org.apache.commons.codec.binary.Base64
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.lang.RuntimeException
import java.util.UUID

private val logger = KotlinLogging.logger {}

@ConditionalOnPropertyMissingOrEmpty("integrations.fiona.url", "integrations.herkimer.url")
@Service
class S3DisabledFeature : S3FeatureTemplate() {

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        cmd: AuroraContextCommand
    ): List<Exception> =
        if (findS3Buckets(adc, cmd.applicationFiles).isNotEmpty()) {
            listOf(IllegalArgumentException("S3 storage is not available in this cluster=${adc.cluster}"))
        } else {
            emptyList()
        }
}

@ConditionalOnProperty(value = ["integrations.fiona.url", "integrations.herkimer.url"])
@Service
class S3Feature(
    val s3Provisioner: S3Provisioner,
    val herkimerService: HerkimerService,
    @Value("\${application.deployment.id}") val booberApplicationdeploymentId: String,
    @Value("\${openshift.cluster}") val cluster: String,
    @Value("\${minio.bucket.region:us-east-1}") val defaultBucketRegion: String
) : S3FeatureTemplate() {

    override fun enable(header: AuroraDeploymentSpec): Boolean {
        return !header.isJob
    }

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, cmd: AuroraContextCommand) {
        val bucketNameAndObjectAreas = findS3Buckets(adc, cmd.applicationFiles).groupBy { it.bucketName }
        resources.filter { it.resource.metadata.name.endsWith("-s3") }
            .filter { it.resource is Secret }
            .map { it.resource as Secret }
            .forEach { secret ->
                val bucketName = String(Base64.decodeBase64(secret.data["bucketName"]))

                bucketNameAndObjectAreas[bucketName]?.map { it.name }
                    ?.forEach { bucketObjectArea ->
                        val envVars = secret.createEnvVarRefs(prefix = "S3_${bucketObjectArea}_")
                        resources.addEnvVar(envVars, javaClass)
                    }
                    ?: throw RuntimeException("Failed during creation of envVars, a secret with name=${secret.metadata.name} has been created but the bucket or objectArea does not exist")
            }
    }

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        cmd: AuroraContextCommand
    ): List<Exception> {
        val buckets = findS3Buckets(adc, cmd.applicationFiles)

        if (fullValidation && adc.cluster == cluster && buckets.isNotEmpty()) {

            val nameAndCredentials = getBucketCredentials()

            val bucketsNotFound = buckets.mapNotNull {
                val credentials = nameAndCredentials[it.bucketName]
                if (credentials == null) IllegalArgumentException("Could not find credentials for bucket with name=${it.bucketName}, please register the credentials")
                else null
            }

            if (bucketsNotFound.isNotEmpty()) return bucketsNotFound
        }

        return emptyList()
    }

    override fun generate(adc: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraResource> {
        val buckets = findS3Buckets(adc, cmd.applicationFiles)
        if (buckets.isEmpty()) return emptySet()

        val resourceWithClaims =
            herkimerService.getClaimedResources(
                claimOwnerId = adc.applicationDeploymentId,
                resourceKind = ResourceKind.MinioPolicy
            ).associateBy { it.name }

        val s3Credentials = buckets.associate { bucket ->
            val credentials = resourceWithClaims[bucket.bucketName]?.claims?.singleOrNull()?.credentials

            if (credentials != null) return@associate bucket to jacksonObjectMapper().convertValue<S3Credentials>(
                credentials
            )

            val request = S3ProvisioningRequest(
                bucketName = bucket.bucketName,
                path = UUID.randomUUID().toString().replace("-", ""),
                userName = adc.applicationDeploymentId,
                access = listOf(S3Access.WRITE, S3Access.DELETE, S3Access.READ)
            )

            bucket to s3Provisioner.provision(request).toS3Credentials(bucket.bucketName, request.path)
                .also {
                    herkimerService.createResourceAndClaim(
                        ownerId = adc.applicationDeploymentId,
                        resourceKind = ResourceKind.MinioPolicy,
                        resourceName = it.bucketName,
                        credentials = it
                    )
                }
        }

        val s3Secret = s3Credentials.createS3Secrets(adc.namespace, adc.name)

        return s3Secret.map { it.generateAuroraResource() }.toSet()
    }

    private fun getBucketCredentials(): Map<String, JsonNode?> =
        herkimerService.getClaimedResources(
            claimOwnerId = booberApplicationdeploymentId,
            resourceKind = ResourceKind.MinioPolicy
        ).associate {
            it.name to it.claims?.singleOrNull()?.credentials
        }

    private fun S3ProvisioningResult.toS3Credentials(bucketName: String, objectPrefix: String) =
        S3Credentials(
            "default",
            serviceEndpoint,
            accessKey,
            secretKey,
            bucketName,
            objectPrefix,
            defaultBucketRegion
        )
}

private const val FEATURE_FIELD_NAME = "s3"

private fun Secret.createEnvVarRefs(properties: List<String> = this.data.map { it.key }, prefix: String = "") =
    properties.map { propertyName ->
        val envVarName = "$prefix$propertyName".toUpperCase()
        val secretName = this.metadata.name
        newEnvVar {
            name = envVarName
            valueFrom {
                secretKeyRef {
                    key = propertyName
                    name = secretName
                    optional = false
                }
            }
        }
    }

fun Map<S3BucketObjectArea, S3Credentials>.createS3Secrets(nsName: String, appName: String) =
    this.map { (s3BucketObjectArea, provisionResult) ->
        newSecret {
            metadata {
                name = "$appName-${s3BucketObjectArea.name}-s3"
                namespace = nsName
            }
            data = provisionResult.run {
                mapOf(
                    "serviceEndpoint" to serviceEndpoint,
                    "accessKey" to accessKey,
                    "secretKey" to secretKey,
                    "bucketRegion" to bucketRegion,
                    "bucketName" to bucketName,
                    "objectPrefix" to objectPrefix
                ).mapValues { Base64.encodeBase64String(it.value.toByteArray()) }
            }
        }
    }

abstract class S3FeatureTemplate : Feature {
    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {

        val s3Handlers = findS3Handlers(cmd.applicationFiles)
        val s3DefaultHandlers = findS3DefaultHandlers(cmd.applicationFiles)
        return setOf(
            AuroraConfigFieldHandler(
                "beta/$FEATURE_FIELD_NAME",
                validator = { it.boolean() },
                defaultValue = false,
                canBeSimplifiedConfig = true
            ),
            AuroraConfigFieldHandler(
                FEATURE_FIELD_NAME,
                validator = { it.boolean() },
                defaultValue = false,
                canBeSimplifiedConfig = true
            )
        ) + s3Handlers + s3DefaultHandlers
    }

    private fun findS3Handlers(applicationFiles: List<AuroraConfigFile>): List<AuroraConfigFieldHandler> =
        applicationFiles.findSubKeysExpanded("s3").flatMap { s3Bucket ->
            if (s3Bucket.isNotEmpty()) {
                listOf(
                    AuroraConfigFieldHandler("$s3Bucket/bucketName"),
                    AuroraConfigFieldHandler("$s3Bucket/objectArea"),
                    AuroraConfigFieldHandler("$s3Bucket/enabled", validator = { it.boolean() }, defaultValue = true)
                )
            } else emptyList()
        }

    private fun findS3DefaultHandlers(applicationFiles: List<AuroraConfigFile>): List<AuroraConfigFieldHandler> =
        listOf(
            AuroraConfigFieldHandler("s3Defaults/bucketName"),
            AuroraConfigFieldHandler("s3Defaults/objectArea")
        )

    fun findS3Buckets(adc: AuroraDeploymentSpec, applicationFiles: List<AuroraConfigFile>): List<S3BucketObjectArea> {

        return if (adc.isSimplifiedAndEnabled("s3") || adc.isSimplifiedAndEnabled("beta/s3")) {
            val defaultS3Bucket = S3BucketObjectArea(
                bucketName = adc["s3Defaults/bucketName"],
                name = adc["s3Defaults/objectArea"]
            )
            listOf(defaultS3Bucket)
        } else {
            applicationFiles.findSubKeysExpanded("s3")
                .mapNotNull { findS3Bucket(it, adc) }
        }
    }

    fun findS3Bucket(
        s3ObjectAreaKey: String,
        adc: AuroraDeploymentSpec
    ): S3BucketObjectArea? {
        if (!adc.get<Boolean>("$s3ObjectAreaKey/enabled")) return null

        return S3BucketObjectArea(
            bucketName = adc.getOrNull("$s3ObjectAreaKey/bucketName") ?: adc["s3Defaults/bucketName"],
            name = adc.getOrNull("$s3ObjectAreaKey/objectArea") ?: s3ObjectAreaKey.substringAfter("s3/")
        )
    }
}

data class S3BucketObjectArea(
    val bucketName: String,
    val name: String
)

data class S3Credentials(
    val objectArea: String,
    val serviceEndpoint: String,
    val accessKey: String,
    val secretKey: String,
    val bucketName: String,
    val objectPrefix: String,
    val bucketRegion: String
)
