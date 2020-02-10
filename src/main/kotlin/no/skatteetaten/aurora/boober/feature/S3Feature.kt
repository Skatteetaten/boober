package no.skatteetaten.aurora.boober.feature

import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newEnvVar
import com.fkorotkov.kubernetes.newSecret
import com.fkorotkov.kubernetes.secretKeyRef
import com.fkorotkov.kubernetes.valueFrom
import io.fabric8.kubernetes.api.model.Secret
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.addEnvVar
import no.skatteetaten.aurora.boober.utils.boolean
import org.apache.commons.codec.binary.Base64
import org.springframework.stereotype.Service

/*
TODO: Implement handler for clusters where s3 is not available

@ConditionalOnPropertyMissingOrEmpty("integrations.s3.url")
@Service
class S3DisabledFeature : Feature {
    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return emptySet()
    }
}
*/

// @ConditionalOnPropertyMissingOrEmpty("integrations.s3.url")
@Service
class S3Feature : Feature {
    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return setOf(
            AuroraConfigFieldHandler(
                "s3",
                validator = { it.boolean() },
                defaultValue = false,
                canBeSimplifiedConfig = true
            )
        )
    }

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, cmd: AuroraContextCommand) {
        val s3SecretResource = resources.find { it.resource.metadata.name == adc.s3SecretName } ?: return

        val s3Secret = s3SecretResource.resource as Secret

        val envVarsFromConfig = s3Secret.data.map { (propertyName, _) ->
            newEnvVar {
                name = "S3_${propertyName.toUpperCase()}"
                valueFrom {
                    secretKeyRef {
                        key = propertyName
                        name = s3Secret.metadata.name
                        optional = false
                    }
                }
            }
        }

        resources.addEnvVar(envVarsFromConfig, this::class.java)
    }

    override fun generate(adc: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraResource> {
        val isS3Enabled: Boolean = adc["s3"]
        if (!isS3Enabled) return emptySet()

        val credentialsSecret = adc.createS3Secret()
        return setOf(generateResource(credentialsSecret))
    }

    fun AuroraDeploymentSpec.createS3Secret(): Secret {
        val adc = this
        return newSecret {
            metadata {
                name = adc.s3SecretName
                namespace = adc.namespace
            }
            data = mapOf(
                "url" to "http://minio-aurora-dev.utv.paas.skead.no",
                "accessKey" to "aurora",
                "secretKey" to "fragleberget"
            ).mapValues { it.value.toByteArray() }.mapValues { Base64.encodeBase64String(it.value) }
        }
    }

    private val AuroraDeploymentSpec.s3SecretName get() = "${this.name}-s3"
}
