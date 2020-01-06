package no.skatteetaten.aurora.boober.feature

import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newSecret
import com.fkorotkov.kubernetes.newVolume
import com.fkorotkov.kubernetes.newVolumeMount
import com.fkorotkov.kubernetes.secret
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.kubernetes.api.model.Secret
import java.io.ByteArrayOutputStream
import java.util.Properties
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.Paths.secretsPath
import no.skatteetaten.aurora.boober.model.addVolumesAndMounts
import no.skatteetaten.aurora.boober.service.resourceprovisioning.StsProvisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.StsProvisioningResult
import no.skatteetaten.aurora.boober.utils.ConditionalOnPropertyMissingOrEmpty
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.normalizeLabels
import org.apache.commons.codec.binary.Base64
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

val AuroraDeploymentSpec.certificateCommonName: String?
    get() {
        val simplified = this.isSimplifiedConfig("certificate")
        if (!simplified) {
            return this.getOrNull("certificate/commonName")
        }

        val value: Boolean = this["certificate"]
        if (!value) {
            return null
        }
        val groupId: String = this.getOrNull<String>("groupId") ?: ""
        return "$groupId.${this.name}"
    }

private val logger = KotlinLogging.logger { }

@ConditionalOnPropertyMissingOrEmpty("integrations.skap.url")
@Service
class CertificateDisabledFeature : Feature {
    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return setOf(
            AuroraConfigFieldHandler(
                "certificate",
                defaultValue = false,
                canBeSimplifiedConfig = true
            ),
            AuroraConfigFieldHandler("certificate/commonName"),
            header.groupIdHandler
        )
    }

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        cmd: AuroraContextCommand
    ): List<Exception> {
        adc.certificateCommonName?.let {
            if (it.isNotEmpty()) {
                return listOf(RuntimeException("STS is not supported."))
            }
        }
        return emptyList()
    }
}

@Service
@ConditionalOnProperty("integrations.skap.url")
class CertificateFeature(val sts: StsProvisioner) : Feature {
    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return setOf(
            AuroraConfigFieldHandler(
                "certificate",
                defaultValue = false,
                canBeSimplifiedConfig = true
            ),
            AuroraConfigFieldHandler("certificate/commonName"),
            header.groupIdHandler
        )
    }

    override fun generate(adc: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraResource> {
        return adc.certificateCommonName?.let {
            val result = sts.generateCertificate(it, adc.name, adc.envName)

            val secret = StsSecretGenerator.create(adc.name, result, adc.namespace)
            setOf(generateResource(secret))
        } ?: emptySet<AuroraResource>()
    }

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, cmd: AuroraContextCommand) {
        adc.certificateCommonName?.let {
            val baseUrl = "$secretsPath/${adc.name}-cert"
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
            resources.addVolumesAndMounts(stsVars, listOf(volume), listOf(mount), this::class.java)
        }
    }

    fun willCreateResource(spec: AuroraDeploymentSpec) = spec.certificateCommonName != null
}

object StsSecretGenerator {

    const val RENEW_AFTER_LABEL = "stsRenewAfter"
    const val APP_ANNOTATION = "gillis.skatteetaten.no/app"
    const val COMMON_NAME_ANNOTATION = "gillis.skatteetaten.no/commonName"

    fun create(
        appName: String,
        stsProvisionResults: StsProvisioningResult,
        secretNamespace: String,
        certSuffix: String = "cert"
    ): Secret {

        val secretName = "$appName-$certSuffix"
        val baseUrl = "$secretsPath/$secretName/keystore.jks"
        val cert = stsProvisionResults.cert
        return newSecret {
            metadata {
                labels =
                    mapOf(StsSecretGenerator.RENEW_AFTER_LABEL to stsProvisionResults.renewAt.epochSecond.toString()).normalizeLabels()
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
                "descriptor.properties" to createDescriptorFile(
                    jksPath = baseUrl,
                    alias = "ca",
                    storePassword = cert.storePassword,
                    keyPassword = cert.keyPassword
                )
            ).mapValues { Base64.encodeBase64String(it.value) }
        }
    }

    fun create(
        appName: String,
        stsProvisionResults: StsProvisioningResult,
        labels: Map<String, String>,
        ownerReference: OwnerReference,
        namespace: String
    ): Secret {

        val secret = create(appName, stsProvisionResults, namespace)
        secret.metadata.labels = labels.addIfNotNull(secret.metadata?.labels).normalizeLabels()
        secret.metadata.ownerReferences = listOf(ownerReference)
        return secret
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
