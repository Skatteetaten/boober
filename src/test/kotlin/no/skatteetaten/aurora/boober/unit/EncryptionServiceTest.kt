package no.skatteetaten.aurora.boober.unit

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.micrometer.core.instrument.Metrics
import java.util.Base64
import no.skatteetaten.aurora.AuroraMetrics
import no.skatteetaten.aurora.boober.service.EncryptionService
import org.encryptor4j.factory.AbsKeyFactory
import org.encryptor4j.factory.KeyFactory
import org.junit.jupiter.api.Test

class EncryptionServiceTest {

    val keyFactory: KeyFactory = object : AbsKeyFactory("AES", 128) {}

    val service = EncryptionService(
        "komogsyngensang",
        keyFactory,
        AuroraMetrics(Metrics.globalRegistry)
    )

    val message = "FOO=BAR".toByteArray()

    @Test
    fun `test encrypt and decrypt`() {
        val encrypted = service.encrypt(message)
        val result = service.decrypt(encrypted)
        assertThat(result).isEqualTo(message)
    }

    @Test
    fun `decrypt version 1`() {

        val base64Message = Base64.getEncoder().encodeToString(message).toByteArray()

        val encrypted = service.encrypt(base64Message).replace("Boober:2", "Boober:1")

        val decrypted = service.decrypt(encrypted)
        assertThat(decrypted).isEqualTo(message)
    }
}
