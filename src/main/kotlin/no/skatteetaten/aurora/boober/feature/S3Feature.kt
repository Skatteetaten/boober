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

private val logger = KotlinLogging.logger {}

@ConditionalOnPropertyMissingOrEmpty("integrations.fiona.url", "integrations.herkimer.url")
@Service
class S3DisabledFeature(
    @Value("\${boober.productionlevel}") productionLevel: String
) : S3FeatureTemplate(productionLevel) {

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
    @Value("\${boober.productionlevel}") productionLevel: String
) : S3FeatureTemplate(productionLevel) {

    override fun enable(header: AuroraDeploymentSpec): Boolean {
        return !header.isJob
    }

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, cmd: AuroraContextCommand) {
        resources.filter { it.resource.metadata.name.endsWith("-s3") }
            .filter{it.resource is Secret}
            .map { it.resource as Secret }
            .forEach {
                val bucketSuffix = String(Base64.decodeBase64(it.data["bucketName"])).substringAfterLast("-").toUpperCase()
                val envVars = it.createEnvVarRefs(prefix = "S3_${bucketSuffix}_")
                resources.addEnvVar(envVars, javaClass)
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
                val credentials = nameAndCredentials[it.name]
                if (credentials == null) IllegalArgumentException("Could not find credentials for bucket with name=${it.name}, please register the credentials")
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

        val provisioningResults = buckets.map { bucket ->
            val credentials = resourceWithClaims[bucket.name]?.claims?.singleOrNull()?.credentials

            if (credentials != null) return@map jacksonObjectMapper().convertValue<S3ProvisioningResult>(credentials)

            val request = S3ProvisioningRequest(
                bucketName = bucket.name,
                path = adc.applicationDeploymentId,
                userName = adc.applicationDeploymentId,
                access = listOf(S3Access.WRITE, S3Access.DELETE, S3Access.READ)
            )

            s3Provisioner.provision(request).also {
                herkimerService.createResourceAndClaim(
                    adc.applicationDeploymentId,
                    ResourceKind.MinioPolicy,
                    it.bucketName,
                    it
                )
            }
        }

        val s3Secret = provisioningResults.createS3Secrets(adc.namespace, adc.name)

        return s3Secret.map { it.generateAuroraResource() }.toSet()
    }

    private fun getBucketCredentials(): Map<String, JsonNode?> =
        herkimerService.getClaimedResources(
            claimOwnerId = booberApplicationdeploymentId,
            resourceKind = ResourceKind.MinioPolicy
        ).associate {
            it.name to it.claims?.singleOrNull()?.credentials
        }
}

private const val FEATURE_FIELD_NAME = "s3"

private val AuroraDeploymentSpec.s3SecretName get() = "${this.name}-s3"

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

fun List<S3ProvisioningResult>.createS3Secrets(nsName: String, appName: String) = map {
    newSecret {
        metadata {
            name = "$appName-${it.bucketName.substringAfterLast("-")}-s3" // TODO: possible problem with bucketname that has "-" in its suffix
            namespace = nsName
        }
        data = mapOf(
            "serviceEndpoint" to it.serviceEndpoint,
            "accessKey" to it.accessKey,
            "secretKey" to it.secretKey,
            "bucketRegion" to it.bucketRegion,
            "bucketName" to it.bucketName,
            "objectPrefix" to it.objectPrefix
        ).mapValues { Base64.encodeBase64String(it.value.toByteArray()) }
    }
}

    abstract class S3FeatureTemplate(private val productionLevel: String) : Feature {
        override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {

            val s3Handlers = findS3Handlers(cmd.applicationFiles)
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
            ) + s3Handlers
        }
        fun findS3Handlers(applicationFiles: List<AuroraConfigFile>): List<AuroraConfigFieldHandler> =
            applicationFiles.findSubKeysExpanded("s3").flatMap { s3Bucket ->
                if (s3Bucket.isNotEmpty()) {
                    listOf(
                        AuroraConfigFieldHandler("$s3Bucket/name", defaultValue = "default"),
                        AuroraConfigFieldHandler("$s3Bucket/enabled", validator = { it.boolean() }, defaultValue = true)
                    )
                } else emptyList()
            }

        fun findS3Buckets(adc: AuroraDeploymentSpec, applicationFiles: List<AuroraConfigFile>): List<S3Bucket> =
            if (adc.isSimplifiedAndEnabled("s3")) {
                listOf(S3Bucket(adc.deduceBucketName()))
            } else {
                applicationFiles.findSubKeysExpanded("s3").mapNotNull {
                    if (!adc.get<Boolean>("$it/enabled")) return@mapNotNull null

                    val bucketSuffix = adc.get<String>("$it/name").let { name ->
                        if (name == "default" || name.isNullOrBlank()) {
                            it.substringAfter("s3/")
                        } else {
                            name
                        }
                    }
                    S3Bucket(
                        name = adc.deduceBucketName(bucketSuffix)
                    )
                }
            }

        private fun AuroraDeploymentSpec.deduceBucketName(suffix: String = "default") = "$affiliation-bucket-$productionLevel-$suffix"
    }

    data class S3Bucket(
        val name: String
    )
