package no.skatteetaten.aurora.boober.service.internal

import io.fabric8.kubernetes.api.model.Secret
import no.skatteetaten.aurora.boober.service.resourceprovisioning.StsProvisioningResult

object StsSecretGenerator {

    @JvmStatic
    fun create(appName: String, stsProvisionResults: StsProvisioningResult, labels: Map<String, String>): Secret {

        val secretName = "$appName-cert"
        val baseUrl = "/u01/secrets/app/$secretName/keystore.jsk"

        val cert = stsProvisionResults.cert
        return SecretGenerator.create(
            secretName = secretName,
            secretLabels = labels,
            secretData = mapOf(
                "privatekey.key" to cert.key,
                "keystore.jks" to cert.keystore,
                "certificate.crt" to cert.crt,
                "desscriptor.properties" to createDescriptorFile(baseUrl, "ca", cert.storePassword, cert.keyPassword)
            )
        )
    }

    private fun createDescriptorFile(
        jksPath: String,
        alias: String,
        storePassword: String,
        keyPassword: String
    ): ByteArray {
        return """
            keystore-file=${jksPath}}
            alias=$alias
            store-password=$storePassword
            key-password=$keyPassword
            """.trimIndent().toByteArray()
    }
}