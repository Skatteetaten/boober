package no.skatteetaten.aurora.boober.service.internal

import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.kubernetes.api.model.Secret
import no.skatteetaten.aurora.boober.service.resourceprovisioning.StsProvisioningResult
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import java.io.ByteArrayOutputStream
import java.util.Properties

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
        return SecretGenerator.create(
            secretName = secretName,
            secretLabels = labels.addIfNotNull(RENEW_AFTER_LABEL to stsProvisionResults.renewAt.epochSecond.toString()),
            secretData = mapOf(
                "privatekey.key" to cert.key,
                "keystore.jks" to cert.keystore,
                "certificate.crt" to cert.crt,
                "descriptor.properties" to createDescriptorFile(baseUrl, "ca", cert.storePassword, cert.keyPassword)
            ),
            secretAnnotations = mapOf(
                APP_ANNOTATION to appName,
                COMMON_NAME_ANNOTATION to stsProvisionResults.cn
            ),
            ownerReference = ownerReference,
            secretNamespace = namespace
        )
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