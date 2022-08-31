package no.skatteetaten.aurora.boober.service

import java.security.Key
import java.security.Security
import java.security.spec.KeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class EncryptionWrapper(
    @Value("\${boober.encrypt.key}") val key: String,
) {
    private val cipherThreadLocal: ThreadLocal<Cipher> = ThreadLocal()
    private val providerName: String
    private val strongKey: Key
    val iterationCount = 65536

    init {
        val bouncyCastleProvider = org.bouncycastle.jce.provider.BouncyCastleProvider()
        providerName = bouncyCastleProvider.name
        Security.getProvider(providerName) ?: Security.addProvider(bouncyCastleProvider)
        strongKey = keyFromPassword(key.toCharArray())
    }

    fun encrypt(message: ByteArray): ByteArray = runCipher(CipherMode.ENCRYPT_MODE, message)
    fun decrypt(message: ByteArray): ByteArray = runCipher(CipherMode.DECRYPT_MODE, message)

    private fun keyFromPassword(password: CharArray?): Key {
        val spec: KeySpec = PBEKeySpec(password, salt, iterationCount, 128)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val tmp = factory.generateSecret(spec)

        return SecretKeySpec(tmp.encoded, "AES")
    }

    private fun runCipher(cipherMode: CipherMode, message: ByteArray): ByteArray {
        val mode = when (cipherMode) {
            CipherMode.ENCRYPT_MODE -> Cipher.ENCRYPT_MODE
            CipherMode.DECRYPT_MODE -> Cipher.DECRYPT_MODE
        }
        val cipher = getCipher()

        cipher.init(mode, strongKey)
        return cipher.doFinal(message) ?: throw IllegalStateException("Could not encrypt message")
    }

    private fun getCipher(): Cipher = cipherThreadLocal.get() ?: createAndSetCipher()

    private fun createAndSetCipher(): Cipher {
        val cipher = Cipher.getInstance("AES", providerName) ?: throw IllegalStateException("Could not find cipher")

        cipherThreadLocal.set(cipher)
        return cipher
    }
}

private val salt = byteArrayOf(
    73, 32, -12, -103, 88, 14, -44, 9, -119, -42, 5, -63, 102, -11, -104,
    66, -17, 112, 55, 44, 18, -46, 30, -6, -55, 28, -54, 12, 39, 110, 63, 125
)

enum class CipherMode {
    ENCRYPT_MODE,
    DECRYPT_MODE
}
