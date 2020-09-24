package no.skatteetaten.aurora.boober.feature

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
import no.skatteetaten.aurora.boober.service.ProvisioningException
import no.skatteetaten.aurora.boober.service.ResourceHerkimer
import no.skatteetaten.aurora.boober.service.ResourceKind
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3Access
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3Provisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3ProvisioningRequest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3ProvisioningResult
import no.skatteetaten.aurora.boober.utils.boolean
import org.apache.commons.codec.binary.Base64
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.stereotype.Service
import javax.annotation.PostConstruct

private val logger = KotlinLogging.logger {}

@ConditionalOnMissingBean(S3Provisioner::class)
@Service
class S3DisabledFeature : Feature {

    @PostConstruct
    fun init() {
        logger.info("S3 feature is disabled since no ${S3Provisioner::class.simpleName} is available")
    }

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand) =
        emptySet<AuroraConfigFieldHandler>()
}

@ConditionalOnBean(S3Provisioner::class)
@Service
class S3Feature(
    val s3Provisioner: S3Provisioner,
    val herkimerService: HerkimerService,
    @Value("\${application.deployment.id}") val booberApplicationDeploymentId: String
) : Feature {

    override fun enable(header: AuroraDeploymentSpec): Boolean {
        return !header.isJob
    }

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

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, cmd: AuroraContextCommand) {

        val s3Secret = resources.find { it.resource.metadata.name == adc.s3SecretName }
            ?.let { it.resource as Secret } ?: return

        val envVars = s3Secret.createEnvVarRefs(prefix = "S3_")
        addEnvVarsToDcContainers(resources, envVars)
    }

    override fun generate(adc: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraResource> {
        if (!adc.isS3Enabled) return emptySet()

        val resourceWithClaims =
            herkimerService.getClaimedResources(adc.applicationDeploymentId, ResourceKind.MinioPolicy).firstOrNull()

        val region = "us-east-01"
        val bucketName = "${adc.affiliation}_bucket_t_${adc.cluster}_default"
        val result =
            if (resourceWithClaims?.claims != null) jacksonObjectMapper().convertValue(resourceWithClaims.claims.single().credentials)
            else {
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

        val s3Secret = result.createS3Secret(adc.namespace, adc.s3SecretName)

        return setOf(s3Secret.generateAuroraResource())
    }
}

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
    ).mapValues { it.value.toByteArray() }.mapValues { Base64.encodeBase64String(it.value) }
}
