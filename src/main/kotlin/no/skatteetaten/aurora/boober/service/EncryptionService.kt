package no.skatteetaten.aurora.boober.service

import org.encryptor4j.Encryptor
import org.encryptor4j.factory.KeyFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File
import java.util.*


@Service
class EncryptionService(@Value("\${boober.encrypt.key}") val key: String) {

    val LINE_SEPERATOR = "\n"
    val version = "Boober:1"
    val encryptor = Encryptor(KeyFactory.AES.keyFromPassword(key.toCharArray()), "AES/GCM/PKCS5Padding", 16, 128)

    fun encrypt(message: String): String {
        val result = encryptor.encrypt(message.toByteArray())
        val encoded = Base64.getEncoder().encodeToString(result)

        val body = "$version$LINE_SEPERATOR$encoded"
        return body
    }

    fun decrypt(source: String): String {
        val split = source.split(LINE_SEPERATOR)
        //If/when we use new versions of encryption here we can use an encryptor for that specific version when we decode.
        return String(encryptor.decrypt(Base64.getDecoder().decode(split[1])))
    }
}
