package no.skatteetaten.aurora.boober.unit

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.micrometer.core.instrument.Metrics
import no.skatteetaten.aurora.AuroraMetrics
import no.skatteetaten.aurora.boober.service.EncryptionService
import org.junit.jupiter.api.Test
import java.util.Base64
import no.skatteetaten.aurora.boober.service.EncryptorWrapper

class EncryptionServiceTest {
    val encryptor = EncryptorWrapper(
        "komogsyngensang"
    )
    val service = EncryptionService(encryptor, AuroraMetrics(Metrics.globalRegistry))

    val message = "FOO=BAR".toByteArray()

    @Test
    fun `test encrypt and decrypt`() {
        val encrypted = service.encrypt(message)

        assertThat(encrypted).isEqualTo(
            "Boober:2\n" +
                "OBd4no2FtIijAmEN8sgYgw=="
        )

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
