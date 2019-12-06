package no.skatteetaten.aurora.boober.feature

import com.fkorotkov.kubernetes.newVolume
import com.fkorotkov.kubernetes.newVolumeMount
import com.fkorotkov.kubernetes.secret
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.model.AuroraConfigException
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.Paths.secretsPath
import no.skatteetaten.aurora.boober.model.addVolumesAndMounts
import no.skatteetaten.aurora.boober.service.resourceprovisioning.StsProvisioner
import no.skatteetaten.aurora.boober.utils.ConditionalOnPropertyMissingOrEmpty
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

val AuroraDeploymentSpec.stsCommonName: String?
    get() {
        val simplified = this.isSimplifiedConfig("sts")
        if (!simplified) {
            return this.getOrNull("sts/cn")
        }

        val value: Boolean = this["sts"]
        if (!value) {
            return null
        }
        val groupId: String = this.getOrNull<String>("groupId") ?: ""
        return "$groupId.${this.name}"
    }

private val logger = KotlinLogging.logger { }

// TODO: Should we change this to allow more the one?
@ConditionalOnPropertyMissingOrEmpty("integrations.skap.url")
@Service
class StsDisabledFeature : Feature {
    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return setOf(
            AuroraConfigFieldHandler(
                "sts",
                defaultValue = false,
                canBeSimplifiedConfig = true
            ),
            AuroraConfigFieldHandler("sts/cn"),
            header.groupIdHandler
        )
    }

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        cmd: AuroraContextCommand
    ): List<Exception> {
        adc.stsCommonName?.let {
            if (it.isNotEmpty()) {
                return listOf(AuroraConfigException("STS is not supported."))
            }
        }
        return emptyList()
    }
}

@Service
@ConditionalOnProperty(value = ["integrations.skap.url", "integrations.bigbird.url"])
class StsFeature(
    val sts: StsProvisioner,
    @Value("\${integrations.bigbird.url}") val bigBirdUrl: String
) : Feature {
    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return setOf(
            AuroraConfigFieldHandler(
                "sts",
                defaultValue = false,
                canBeSimplifiedConfig = true
            ),
            AuroraConfigFieldHandler("sts/cn"),
            header.groupIdHandler
        )
    }

    override fun generate(adc: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraResource> {
        return adc.stsCommonName?.let {
            val result = sts.generateCertificate(it, adc.name, adc.envName)

            val secret = StsSecretGenerator.create(adc.name, result, adc.namespace, "sts")
            setOf(generateResource(secret))
        } ?: emptySet()
    }

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, cmd: AuroraContextCommand) {
        adc.stsCommonName?.let {
            val baseUrl = "$secretsPath/${adc.name}-cert"
            val stsVars = mapOf(
                "STS_CERTIFICATE_URL" to "$baseUrl/certificate.crt",
                "STS_PRIVATE_KEY_URL" to "$baseUrl/privatekey.key",
                "STS_KEYSTORE_DESCRIPTOR" to "$baseUrl/descriptor.properties",
                "VOLUME_${adc.name}_STS".toUpperCase() to baseUrl,
                "STS_DISCOVERY_URL" to bigBirdUrl

            ).toEnvVars()

            val mount = newVolumeMount {
                name = "${adc.name}-sts"
                mountPath = baseUrl
            }

            val volume = newVolume {
                name = "${adc.name}-sts"
                secret {
                    secretName = "${adc.name}-sts"
                }
            }
            resources.addVolumesAndMounts(stsVars, listOf(volume), listOf(mount), this::class.java)
        }
    }
}
