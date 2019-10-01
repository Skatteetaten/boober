package no.skatteetaten.aurora.boober.feature

import com.fkorotkov.kubernetes.*
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.kubernetes.api.model.Secret
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraContextCommand
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.AuroraResource
import no.skatteetaten.aurora.boober.service.Feature
import no.skatteetaten.aurora.boober.service.addVolumesAndMounts
import no.skatteetaten.aurora.boober.service.resourceprovisioning.StsProvisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.StsProvisioningResult
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.whenTrue
import org.apache.commons.codec.binary.Base64
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.util.*

@Service
class StsFeature(val sts: StsProvisioner) : Feature {
    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return setOf(
                AuroraConfigFieldHandler("certificate", defaultValue = false, canBeSimplifiedConfig = true),
                AuroraConfigFieldHandler("certificate/commonName")
        )
    }

    override fun generate(adc: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraResource> {

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

    fun findCertificate(adc: AuroraDeploymentSpec, name: String): String? {

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

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, cmd: AuroraContextCommand) {
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
            resources.addVolumesAndMounts(stsVars, listOf(volume), listOf(mount))
        }
    }
}

object StsSecretGenerator {

    const val RENEW_AFTER_LABEL = "stsRenewAfter"
    const val APP_ANNOTATION = "gillis.skatteetaten.no/app"
    const val COMMON_NAME_ANNOTATION = "gillis.skatteetaten.no/commonName"

    @JvmStatic
    fun create(
        appName: String,
        stsProvisionResults: StsProvisioningResult,
        labels: Map<String, String>,
        ownerReference: OwnerReference,
        namespace: String
    ): Secret {

        val secretName = "$appName-cert"
        val baseUrl = "/u01/secrets/app/$secretName/keystore.jks"

        val cert = stsProvisionResults.cert
        val secretAnnotations = mapOf(
                APP_ANNOTATION to appName,
                COMMON_NAME_ANNOTATION to stsProvisionResults.cn
        )
        return newSecret {
            metadata {
                this.labels = labels.addIfNotNull(RENEW_AFTER_LABEL to stsProvisionResults.renewAt.epochSecond.toString())
                name = secretName
                this.namespace = namespace
                ownerReferences = listOf(element = ownerReference)
                secretAnnotations.isNotEmpty().whenTrue {
                    annotations = secretAnnotations
                }
            }
            mapOf(
                    "privatekey.key" to cert.key,
                    "keystore.jks" to cert.keystore,
                    "certificate.crt" to cert.crt,
                    "descriptor.properties" to createDescriptorFile(baseUrl, "ca", cert.storePassword, cert.keyPassword)
            ).let {
                data = it.mapValues { Base64.encodeBase64String(it.value) }
            }
        }
    }

    fun createDescriptorFile(
        jksPath: String,
        alias: String,
        storePassword: String,
        keyPassword: String
    ): ByteArray {
        return Properties().run {
            put("keystore-file", jksPath)
            put("alias", alias)
            put("store-password", storePassword)
            put("key-password", keyPassword)

            val bos = ByteArrayOutputStream()
            store(bos, "")
            bos.toByteArray()
        }
    }
}
