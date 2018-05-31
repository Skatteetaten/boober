package no.skatteetaten.aurora.boober.service.internal

import io.fabric8.kubernetes.api.model.Secret
import no.skatteetaten.aurora.boober.service.resourceprovisioning.StsProvisioningResult
import java.io.ByteArrayOutputStream
import java.util.Properties

object StsSecretGenerator {

    @JvmStatic
    fun create(appName: String, stsProvisionResults: StsProvisioningResult, labels: Map<String, String>): Secret {

        val secretName="$appName-cert"
        val baseUrl = "/u01/secrets/app/$secretName/keystore.jks"

        val cert=stsProvisionResults.cert
        return SecretGenerator.create(
            secretName=secretName,
            secretLabels=labels,
            secretData = mapOf(
                "privatekey.key" to cert.key,
                "keystore.jks" to cert.keystore,
                "certificate.crt" to cert.crt,
                "descriptor.properties" to createDescriptorFile(baseUrl, "ca", cert.storePassword, cert.keyPassword)
            )
        )
    }

    private fun createDescriptorFile(
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