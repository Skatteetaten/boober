package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newEnvVar
import com.fkorotkov.kubernetes.newSecret
import com.fkorotkov.kubernetes.secretKeyRef
import com.fkorotkov.kubernetes.valueFrom
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.Secret
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.addEnvVar
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
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

@ConditionalOnPropertyMissingOrEmpty("integrations.fiona.url")
@Service
class S3DisabledFeature : S3FeatureTemplate() {
    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        cmd: AuroraContextCommand
    ): List<Exception> =
        if (adc.isS3Enabled) {
            listOf(IllegalArgumentException("S3 storage is not available in this cluster=${adc.cluster}"))
        } else {
            emptyList()
        }
}

@ConditionalOnProperty("integrations.fiona.url")
@Service
class S3Feature(
    val s3Provisioner: S3Provisioner,
    val herkimerService: HerkimerService,
    val productionLevels: ProductionLevels,
    @Value("\${boober.applicationdeployment.id}") val booberApplicationdeploymentId: String
) : S3FeatureTemplate() {

    override fun enable(header: AuroraDeploymentSpec): Boolean {
        return !header.isJob
    }

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, cmd: AuroraContextCommand) {

        val s3Secret = resources.find { it.resource.metadata.name == adc.s3SecretName }
            ?.let { it.resource as Secret } ?: return

        val envVars = s3Secret.createEnvVarRefs(prefix = "S3_")
        addEnvVarsToDcContainers(resources, envVars)
    }

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        cmd: AuroraContextCommand
    ): List<Exception> {
        val bucketName = deduceBucketName(adc)
        getBucketCredentials(bucketName)
            ?: return listOf(IllegalArgumentException("Could not find credentials for bucket with name=$bucketName, please register the credentials."))

        return emptyList()
    }

    override fun generate(adc: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraResource> {
        if (!adc.isS3Enabled) return emptySet()

        val bucketName = deduceBucketName(adc)
        getBucketCredentials(bucketName)
            ?: throw IllegalArgumentException("Could not find credentials for bucket with name=$bucketName, please register the credentials.")

        val resourceWithClaims =
            herkimerService.getClaimedResources(adc.applicationDeploymentId, ResourceKind.MinioPolicy).firstOrNull()

        val result =
            when (val credentials = resourceWithClaims?.claims?.singleOrNull()?.credentials) {
                null -> {
                    val request = S3ProvisioningRequest(
                        bucketName = bucketName,
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
                else -> jacksonObjectMapper().convertValue(credentials)
            }

        val s3Secret = result.createS3Secret(adc.namespace, adc.s3SecretName)

        return setOf(s3Secret.generateAuroraResource())
    }

    private fun getBucketCredentials(bucketName: String): JsonNode? =
        herkimerService.getClaimedResources(
            claimOwnerId = booberApplicationdeploymentId,
            resourceKind = ResourceKind.MinioPolicy,
            name = bucketName
        ).singleOrNull()
            ?.claims
            ?.singleOrNull()
            ?.credentials

    private fun deduceBucketName(adc: AuroraDeploymentSpec, bucketNameSuffix: String = "default"): String {
        val productionLevel = productionLevels.findLevelByCluster(adc.cluster)
        return "${adc.affiliation}-bucket-$productionLevel-$bucketNameSuffix"
    }
}

private fun ProductionLevels.findLevelByCluster(cluster: String) =
    this.clusterToLevel[cluster]?.shortFormat
        ?: throw IllegalArgumentException("Could not find productionlevel for cluster=$cluster")

private const val FEATURE_FIELD_NAME = "beta/s3"

private val AuroraDeploymentSpec.s3SecretName get() = "${this.name}-s3"
private val AuroraDeploymentSpec.isS3Enabled: Boolean get() = get(FEATURE_FIELD_NAME)

fun Feature.addEnvVarsToDcContainers(resources: Set<AuroraResource>, envVars: List<EnvVar>) {
    resources.addEnvVar(envVars, this.javaClass)
}

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

fun S3ProvisioningResult.createS3Secret(nsName: String, s3SecretName: String) = newSecret {
    metadata {
        name = s3SecretName
        namespace = nsName
    }
    data = mapOf(
        "serviceEndpoint" to serviceEndpoint,
        "accessKey" to accessKey,
        "secretKey" to secretKey,
        "bucketRegion" to bucketRegion,
        "bucketName" to bucketName,
        "objectPrefix" to objectPrefix
    ).mapValues { Base64.encodeBase64String(it.value.toByteArray()) }
}

abstract class S3FeatureTemplate : Feature {
    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return setOf(
            AuroraConfigFieldHandler(
                FEATURE_FIELD_NAME,
                validator = { it.boolean() },
                defaultValue = false,
                canBeSimplifiedConfig = true
            )
        )
    }
}

@ConfigurationProperties("boober.productionlevel")
@ConstructorBinding
data class ProductionLevels(
    val clusterToLevel: Map<String, ProductionLevel>
) {
    enum class ProductionLevel(val shortFormat: String) { Utvikling("u"), Test("t"), Produksjon("p") }
}
