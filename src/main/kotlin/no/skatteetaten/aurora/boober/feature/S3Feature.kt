package no.skatteetaten.aurora.boober.feature

import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newEnvVar
import com.fkorotkov.kubernetes.newSecret
import com.fkorotkov.kubernetes.secretKeyRef
import com.fkorotkov.kubernetes.valueFrom
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.Secret
import javax.annotation.PostConstruct
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.addEnvVar
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3Provisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3ProvisioningRequest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3ProvisioningResult
import no.skatteetaten.aurora.boober.utils.boolean
import org.apache.commons.codec.binary.Base64
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

@ConditionalOnMissingBean(S3Provisioner::class)
@Service
class S3DisabledFeature : Feature {

    override fun enable(header: AuroraDeploymentSpec): Boolean {
        return header.type != TemplateType.job
    }

    @PostConstruct
    fun init() {
        logger.info("S3 feature is disabled since no ${S3Provisioner::class.simpleName} is available")
    }

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand) = emptySet<AuroraConfigFieldHandler>()
}

@ConditionalOnBean(S3Provisioner::class)
@Service
class S3Feature(val s3Provisioner: S3Provisioner) : Feature {

    override fun enable(header: AuroraDeploymentSpec): Boolean {
        return header.type != TemplateType.job
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

        val request = adc.createS3ProvisioningRequest()
        val result = s3Provisioner.provision(request)
        val s3Secret = result.createS3Secret(adc.namespace, adc.s3SecretName)

        return setOf(s3Secret.generateAuroraResource())
    }
}

private const val FEATURE_FIELD_NAME = "beta/s3"

private fun AuroraDeploymentSpec.createS3ProvisioningRequest() = S3ProvisioningRequest(affiliation, envName, name)
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
