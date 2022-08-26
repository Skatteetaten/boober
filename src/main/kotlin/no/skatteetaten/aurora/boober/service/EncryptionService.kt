package no.skatteetaten.aurora.boober.service

import java.util.Base64
import org.springframework.stereotype.Service
import no.skatteetaten.aurora.AuroraMetrics

@Service
class EncryptionService(
    val encryptor: EncryptionWrapper,
    val metrics: AuroraMetrics
) {

    // Version 1 of the file format always contained base64 content (after the file was decrypted).
    val VERSION1 = "Boober:1"

    // Version 2 does not (necessarily) contain string based contents at all (makes no assumptions - it's all bytes).
    val VERSION2 = "Boober:2"

    val version = VERSION2

    val LINE_SEPERATOR = "\n"

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
