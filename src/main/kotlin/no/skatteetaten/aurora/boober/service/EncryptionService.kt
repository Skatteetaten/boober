package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.AuroraMetrics
import org.encryptor4j.Encryptor
import org.encryptor4j.factory.KeyFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.charset.Charset
import java.security.Security
import java.util.*

@Service
class EncryptionService(
        @Value("\${boober.encrypt.key}") val key: String,
        keyFactory: KeyFactory,
        val metrics: AuroraMetrics) {

    val version = "Boober:1"
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

    fun encrypt(message: String): String {
        return metrics.withMetrics("encrypt", {
            val result = encryptor.encrypt(message.toByteArray())
            val encoded = Base64.getEncoder().encodeToString(result)

            "$version$LINE_SEPERATOR$encoded"
        })
    }

    fun decrypt(source: String): String {

        return metrics.withMetrics("decrypt", {
            val split = source.split(LINE_SEPERATOR)
            //If/when we use new versions of encryption here we can use an encryptor for that specific version when we decode.
            val cipherTextBase64: String = split[1]
            val cipherText: ByteArray = Base64.getDecoder().decode(cipherTextBase64)
            String(encryptor.decrypt(cipherText))
        })
    }
}
