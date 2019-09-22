package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.*
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.api.model.Volume
import io.fabric8.kubernetes.api.model.VolumeMount
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentContext
import no.skatteetaten.aurora.boober.model.Database
import no.skatteetaten.aurora.boober.service.AuroraResource
import no.skatteetaten.aurora.boober.service.Feature
import no.skatteetaten.aurora.boober.service.addEnvVar
import no.skatteetaten.aurora.boober.service.internal.StsSecretGenerator
import no.skatteetaten.aurora.boober.service.resourceprovisioning.StsProvisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.StsProvisioningResult
import no.skatteetaten.aurora.boober.utils.ensureStartWith
import org.apache.commons.codec.binary.Base64
import org.springframework.stereotype.Service

@Service
class StsFeature(val sts: StsProvisioner) : Feature {
    override fun handlers(header: AuroraDeploymentContext): Set<AuroraConfigFieldHandler> {
        return setOf(
                AuroraConfigFieldHandler("certificate", defaultValue = false, canBeSimplifiedConfig = true),
                AuroraConfigFieldHandler("certificate/commonName")
        )
    }

    override fun generate(adc: AuroraDeploymentContext): Set<AuroraResource> {

        // TODO: Handle failure
        return findCertificate(adc, adc.name)?.let {
            val result = sts.generateCertificate("", adc.name, adc.envName)

            val secret = create(adc.name, result, adc.namespace)
            setOf(AuroraResource("${secret.metadata.name}-secret", secret))
        } ?: emptySet<AuroraResource>()

    }


    fun create(
            appName: String,
            stsProvisionResults: StsProvisioningResult,
            secretNamespace: String
    ): Secret {

        val secretName = "$appName-cert"
        val baseUrl = "/u01/secrets/app/$secretName/keystore.jks"
        val cert = stsProvisionResults.cert
        return newSecret {
            metadata {
                labels = mapOf(StsSecretGenerator.RENEW_AFTER_LABEL to stsProvisionResults.renewAt.epochSecond.toString())
                name = secretName
                namespace = secretNamespace
                annotations = mapOf(
                        StsSecretGenerator.APP_ANNOTATION to appName,
                        StsSecretGenerator.COMMON_NAME_ANNOTATION to stsProvisionResults.cn
                )
            }
            data = mapOf(
                    "privatekey.key" to cert.key,
                    "keystore.jks" to cert.keystore,
                    "certificate.crt" to cert.crt,
                    "descriptor.properties" to StsSecretGenerator.createDescriptorFile(baseUrl, "ca", cert.storePassword, cert.keyPassword)
            ).mapValues { Base64.encodeBase64String(it.value) }
        }

    }

    fun findCertificate(adc: AuroraDeploymentContext, name: String): String? {

        val simplified = adc.isSimplifiedConfig("certificate")
        if (!simplified) {
            return adc.getOrNull("certificate/commonName")
        }

        val value: Boolean = adc["certificate"]
        if (!value) {
            return null
        }
        val groupId: String = adc.getOrNull<String>("groupId") ?: ""
        return "$groupId.$name"
    }


    override fun modify(adc: AuroraDeploymentContext, resources: Set<AuroraResource>) {
        if (adc["certificate"]) {
            val baseUrl = "/u01/secrets/app/${adc.name}-cert"
            val stsVars = mapOf(
                    "STS_CERTIFICATE_URL" to "$baseUrl/certificate.crt",
                    "STS_PRIVATE_KEY_URL" to "$baseUrl/privatekey.key",
                    "STS_KEYSTORE_DESCRIPTOR" to "$baseUrl/descriptor.properties",
                    "VOLUME_${adc.name}_CERT".toUpperCase() to baseUrl
            ).toEnvVars()

            val mount = newVolumeMount {
                name = "${adc.name}-cert"
                mountPath = baseUrl
            }

            val volume = newVolume {
                name = "${adc.name}-cert"
                secret {
                    secretName = "${adc.name}-cert"
                }
            }

            resources.forEach {
                if (it.resource.kind == "DeploymentConfig") {
                    val dc: DeploymentConfig = jacksonObjectMapper().convertValue(it.resource)
                    dc.spec.template.spec.volumes.plusAssign(volume)
                    dc.spec.template.spec.containers.forEach { container ->
                        container.volumeMounts.plusAssign(mount)
                        container.env.addAll(stsVars)
                    }
                }
            }
        }
    }


}

