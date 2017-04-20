package no.skatteetaten.aurora.boober.service

import org.encryptor4j.Encryptor
import org.encryptor4j.factory.KeyFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File
import java.util.*


@Service
class EncryptionService(@Value("\${boober.encrypt.key}") val key: String) {

    val encryptor = Encryptor(KeyFactory.AES.keyFromPassword(key.toCharArray()), "AES/GCM/PKCS5Padding", 16, 128);

    fun encrypt(message: String, target: File) {
        val result = encryptor.encrypt(message.toByteArray())
        target.writeBytes(Base64.getEncoder().encode(result))
    }

    fun decrypt(source: File): String {

        val content = source.readBytes()
        return String(encryptor.decrypt(Base64.getDecoder().decode(content)))

    }
}
