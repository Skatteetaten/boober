package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.AuroraMetrics
import org.encryptor4j.Encryptor
import org.encryptor4j.factory.KeyFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.security.Security
import java.util.Base64

@Service
class EncryptionService(
    @Value("\${boober.encrypt.key}") val key: String,
    val keyFactory: KeyFactory,
    val metrics: AuroraMetrics
) {

    // Version 1 of the file format always contained base64 content (after the file was decrypted).
    val VERSION1 = "Boober:1"

    // Version 2 does not (necessarily) contain string based contents at all (makes no assumptions - it's all bytes).
    val VERSION2 = "Boober:2"

    val version = VERSION2

    val LINE_SEPERATOR = "\n"

    final val providerName: String

    init {
        val bouncyCastleProvider = org.bouncycastle.jce.provider.BouncyCastleProvider()
        providerName = bouncyCastleProvider.name
        Security.getProvider(providerName) ?: Security.addProvider(bouncyCastleProvider)
    }

    val encryptor = Encryptor(keyFactory.keyFromPassword(key.toCharArray())).apply {
        setAlgorithmProvider(providerName)
    }

    fun encrypt(message: ByteArray): String {
        return metrics.withMetrics("encrypt") {
            val result = encryptor.encrypt(message)
            val encoded = Base64.getEncoder().encodeToString(result)

            "$version$LINE_SEPERATOR$encoded"
        }
    }

    fun decrypt(source: String): ByteArray {

        return metrics.withMetrics("decrypt") {
            val split = source.split(LINE_SEPERATOR)
            val fileFormatVersion = split[0]
            // If/when we use new versions of encryption here we can use an encryptor for that specific version when we decode.
            val cipherTextBase64: String = split[1]
            val cipherText: ByteArray = Base64.getDecoder().decode(cipherTextBase64)
            val decrypted = encryptor.decrypt(cipherText)

            if (fileFormatVersion == VERSION1) Base64.getDecoder().decode(decrypted) else decrypted
        }
    }
}
