package no.skatteetaten.aurora.boober.service

import org.encryptor4j.Encryptor
import org.encryptor4j.factory.AbsKeyFactory
import org.encryptor4j.factory.KeyFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import java.security.Security
import java.util.*


@Configuration
class EncryptionConfig {

    @Bean
    fun keyFactory(): KeyFactory = object : AbsKeyFactory("AES", 128) {}
}

@Service
class EncryptionService(@Value("\${boober.encrypt.key}") val key: String, keyFactory: KeyFactory) {

    final val providerName: String

    init {
        val bouncyCastleProvider = org.bouncycastle.jce.provider.BouncyCastleProvider()
        providerName = bouncyCastleProvider.name
        Security.getProvider(providerName) ?: Security.addProvider(bouncyCastleProvider)
    }

    val LINE_SEPERATOR = "\n"

    val version = "Boober:1"

    val encryptor = Encryptor(keyFactory.keyFromPassword(key.toCharArray())).apply {
        setAlgorithmProvider(providerName)
    }

    fun encrypt(message: String): String {
        val result = encryptor.encrypt(message.toByteArray())
        val encoded = Base64.getEncoder().encodeToString(result)

        return "$version$LINE_SEPERATOR$encoded"
    }

    fun decrypt(source: String): String {
        val split = source.split(LINE_SEPERATOR)
        //If/when we use new versions of encryption here we can use an encryptor for that specific version when we decode.
        val cipherTextBase64: String = split[1]
        val cipherText: ByteArray = Base64.getDecoder().decode(cipherTextBase64)
        return String(encryptor.decrypt(cipherText))
    }
}
