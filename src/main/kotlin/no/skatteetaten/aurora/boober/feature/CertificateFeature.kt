package no.skatteetaten.aurora.boober.feature

import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newSecret
import com.fkorotkov.kubernetes.newVolume
import com.fkorotkov.kubernetes.newVolumeMount
import com.fkorotkov.kubernetes.secret
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.kubernetes.api.model.Secret
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
import no.skatteetaten.aurora.boober.utils.boolean
import no.skatteetaten.aurora.boober.utils.normalizeLabels
import org.apache.commons.codec.binary.Base64
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.util.Properties

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
                validator = { it.boolean() },
                canBeSimplifiedConfig = true
            ),
            AuroraConfigFieldHandler("certificate/commonName"),
            header.groupIdHandler
        )
    }

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        context: Map<String, Any>
    ): List<Exception> {
        adc.certificateCommonName?.let {
            if (it.isNotEmpty()) {
                return listOf(IllegalArgumentException("STS is not supported."))
            }
        }
        return emptyList()
    }
}

@Service
@ConditionalOnProperty("integrations.skap.url")
class CertificateFeature(val sts: StsProvisioner) : Feature {

    private val suffix = "cert"

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return setOf(
            AuroraConfigFieldHandler(
                "certificate",
                defaultValue = false,
                validator = { it.boolean() },
                canBeSimplifiedConfig = true
            ),
            AuroraConfigFieldHandler("certificate/commonName"),
            header.groupIdHandler
        )
    }

    override fun generate(adc: AuroraDeploymentSpec, context: Map<String, Any>): Set<AuroraResource> {
        return adc.certificateCommonName?.let {
            val result = sts.generateCertificate(it, adc.name, adc.envName)

            val secret = StsSecretGenerator.create(adc.name, result, adc.namespace)
            setOf(generateResource(secret))
        } ?: emptySet()
    }

    override fun modify(
        adc: AuroraDeploymentSpec,
        resources: Set<AuroraResource>,
        context: Map<String, Any>
    ) {
        adc.certificateCommonName?.let {
            StsSecretGenerator.attachSecret(
                appName = adc.name,
                certSuffix = suffix,
                resources = resources,
                source = this::class.java
            )
        }
    }

    fun willCreateResource(spec: AuroraDeploymentSpec) = spec.certificateCommonName != null
}

object StsSecretGenerator {

    const val RENEW_AFTER_LABEL = "stsRenewAfter"
    const val APP_ANNOTATION = "gillis.skatteetaten.no/app"
    const val COMMON_NAME_ANNOTATION = "gillis.skatteetaten.no/commonName"

    private fun createBasePath(sName: String) = "$secretsPath/$sName"

    private fun createSecretName(appName: String, certSuffix: String) = "$appName-$certSuffix"

    fun attachSecret(
        appName: String,
        certSuffix: String,
        resources: Set<AuroraResource>,
        source: Class<out Feature>,
        additionalEnv: Map<String, String> = emptyMap()
    ) {
        val sName = createSecretName(appName, certSuffix)
        val baseUrl = createBasePath(sName)
        val stsVars = mapOf(
            "STS_CERTIFICATE_URL" to "$baseUrl/certificate.crt",
            "STS_PRIVATE_KEY_URL" to "$baseUrl/privatekey.key",
            "STS_KEYSTORE_DESCRIPTOR" to "$baseUrl/descriptor.properties",
            "VOLUME_$sName".toUpperCase() to baseUrl
        ).addIfNotNull(additionalEnv)
            .toEnvVars()

        val mount = newVolumeMount {
            name = sName
            mountPath = baseUrl
        }

        val volume = newVolume {
            name = sName
            secret {
                secretName = sName
            }
        }
        resources.addVolumesAndMounts(stsVars, listOf(volume), listOf(mount), source)
    }

    fun create(
        appName: String,
        stsProvisionResults: StsProvisioningResult,
        secretNamespace: String,
        certSuffix: String = "cert"
    ): Secret {

        val secretName = createSecretName(appName, certSuffix)
        val baseUrl = "${createBasePath(secretName)}/keystore.jks"
        val cert = stsProvisionResults.cert
        return newSecret {
            metadata {
                labels =
                    mapOf(RENEW_AFTER_LABEL to stsProvisionResults.renewAt.epochSecond.toString()).normalizeLabels()
                name = secretName
                namespace = secretNamespace
                annotations = mapOf(
                    APP_ANNOTATION to appName,
                    COMMON_NAME_ANNOTATION to stsProvisionResults.cn
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
        namespace: String,
        suffix: String
    ): Secret {

        val secret = create(
            appName = appName,
            stsProvisionResults = stsProvisionResults,
            secretNamespace = namespace,
            certSuffix = suffix
        )
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
