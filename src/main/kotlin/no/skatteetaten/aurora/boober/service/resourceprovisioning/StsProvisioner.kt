package no.skatteetaten.aurora.boober.service.resourceprovisioning

import io.micrometer.spring.autoconfigure.export.StringToDurationConverter
import no.skatteetaten.aurora.boober.ServiceTypes
import no.skatteetaten.aurora.boober.TargetService
import no.skatteetaten.aurora.boober.model.AuroraCertificateSpec
import no.skatteetaten.aurora.boober.service.ProvisioningException
import no.skatteetaten.aurora.boober.utils.Instants
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.security.KeyStore
import java.time.Instant
import java.util.Base64

class StsCertificate(
    val crt: ByteArray,
    val key: ByteArray,
    val keystore: ByteArray,
    val storePassword: String,
    val keyPassword: String
)



data class StsProvisioningResult(
    val spec: AuroraCertificateSpec,
    val cert: StsCertificate,
    val renewAt: Instant
)

@Service
class StsProvisioner(
    @TargetService(ServiceTypes.SKAP)
    val restTemplate: RestTemplate,
    @Value("\${boober.skap}") val skapUrl: String
) {
    val logger: Logger = LoggerFactory.getLogger(StsProvisioner::class.java)

    fun generateCertificate(command: AuroraCertificateSpec): StsProvisioningResult {

        return try {
            val renewAt = findRenewInstant(command)
            val response = restTemplate.getForEntity(
                "$skapUrl/certificate?cn={commonName}",
                Resource::class.java,
                command.commonName
            )
            val keyPassword = response.headers.getFirst("key-password")
            val storePassword = response.headers.getFirst("store-password")
            val cert = createStsCert(response.body.inputStream, keyPassword, storePassword)
            StsProvisioningResult(
                cert = cert,
                spec = command,
                renewAt = renewAt
            )
        } catch (e: Exception) {
            throw ProvisioningException(
                "Failed provisioning sts certificate with commonName=${command.commonName} ${e.message}",
                e
            )
        }
    }

    companion object {
        @JvmStatic
        fun findRenewInstant(command: AuroraCertificateSpec): Instant {
            val now = Instants.now

            val converter = StringToDurationConverter()

            val ttlDuration = converter.convert(command.ttl)
            val renewBeforeDuration = converter.convert(command.renewBefore)

            if (ttlDuration < renewBeforeDuration) {
                throw IllegalArgumentException("Illegal combination ttl=${command.ttl} and renewBefoew=${command.renewBefore}. renew must be smaller then ttl.")
            }
            return now + ttlDuration - renewBeforeDuration
        }

        @JvmStatic
        fun createStsCert(
            body: InputStream,
            keyPassword: String,
            storePassword: String
        ): StsCertificate {

            val keyStore = KeyStore.getInstance("JKS").apply {
                this.load(body, storePassword.toCharArray())
            }
            val certificate = PEMWriter(PEMWriter.CERTIFICATE_TYPE, keyStore.getCertificate("ca").encoded)
            val key = PEMWriter(PEMWriter.PRIVATE_KEY_TYPE, keyStore.getKey("ca", keyPassword.toCharArray()).encoded)
            val osKey = ByteArrayOutputStream()
            val osCrt = ByteArrayOutputStream()
            val osKeystore = ByteArrayOutputStream()

            key.writeOut(osKey)
            certificate.writeOut(osCrt)
            keyStore.store(osKeystore, storePassword.toCharArray())

            return StsCertificate(
                crt = osCrt.toByteArray(),
                key = osKey.toByteArray(),
                keystore = osKeystore.toByteArray(),
                storePassword = storePassword,
                keyPassword = keyPassword
            )
        }
    }

    internal class PEMWriter(private val type: String, private val encoded: ByteArray) {

        @Throws(IOException::class)
        fun writeOut(os: OutputStream) {
            writeHeader(os)
            writeBody(os)
            writeFooter(os)
        }

        @Throws(IOException::class)
        private fun writeFooter(os: OutputStream) {
            writeLn(os, "-----END $type-----")
        }

        @Throws(IOException::class)
        private fun writeBody(os: OutputStream) {
            val chars = Base64.getEncoder().encode(encoded)
            var index = 0

            while (index < chars.size) {
                val bytesToWrite = Math.min(64, chars.size - index)
                os.write(chars, index, bytesToWrite)
                os.write("\n".toByteArray(ISO8859_1))
                index += bytesToWrite
            }
        }

        @Throws(IOException::class)
        private fun writeHeader(os: OutputStream) {
            writeLn(os, "-----BEGIN $type-----")
        }

        @Throws(IOException::class)
        private fun writeLn(os: OutputStream, line: String) {
            os.write(line.toByteArray(ISO8859_1))
            os.write("\n".toByteArray(ISO8859_1))
        }

        companion object {
            internal val PRIVATE_KEY_TYPE = "PRIVATE KEY"
            internal val CERTIFICATE_TYPE = "CERTIFICATE"
            internal val ISO8859_1 = Charset.forName("ISO8859-1")
        }
    }
}
