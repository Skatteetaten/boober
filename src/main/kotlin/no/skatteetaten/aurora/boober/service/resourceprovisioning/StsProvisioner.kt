package no.skatteetaten.aurora.boober.service.resourceprovisioning

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.Base64
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.io.Resource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import org.springframework.web.util.UriComponentsBuilder
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.ServiceTypes
import no.skatteetaten.aurora.boober.TargetService
import no.skatteetaten.aurora.boober.service.ProvisioningException
import no.skatteetaten.aurora.boober.utils.RetryingRestTemplateWrapper

private val logger = KotlinLogging.logger {}

class StsCertificate(
    val crt: ByteArray,
    val key: ByteArray,
    val keystore: ByteArray,
    val storePassword: String,
    val keyPassword: String,
    val notAfter: Instant
)

data class StsProvisioningResult(
    val cn: String,
    val cert: StsCertificate,
    val renewAt: Instant
)

@Component
@ConditionalOnProperty("integrations.skap.url")
class SkapRestTemplateWrapper(
    @TargetService(ServiceTypes.AURORA) restTemplate: RestTemplate,
    @Value("\${integrations.skap.url}") override val baseUrl: String,
    @Value("\${integrations.skap.retries:3}") override val retries: Int
) : RetryingRestTemplateWrapper(restTemplate = restTemplate, retries = retries, baseUrl = baseUrl)

@Service
@ConditionalOnProperty("integrations.skap.url")
class StsProvisioner(
    val restTemplate: SkapRestTemplateWrapper,
    @Value("\${boober.sts.renewBeforeDays:14}") val renewBeforeDays: Long,
    @Value("\${openshift.cluster}") val cluster: String
) {

    fun generateCertificate(cn: String, name: String, envName: String): StsProvisioningResult {

        val builder = UriComponentsBuilder.fromPath("/certificate")
            .queryParam("cn", cn)
            .queryParam("cluster", cluster)
            .queryParam("name", name)
            .queryParam("namespace", envName)

        val headers = HttpHeaders().apply {
            accept = listOf(MediaType.APPLICATION_OCTET_STREAM)
        }

        return try {
            val response = restTemplate.get(
                headers,
                Resource::class,
                builder.build().encode().toUriString()
            )
            val keyPassword = response.headers.getFirst("key-password")!!
            val storePassword = response.headers.getFirst("store-password")!!
            val cert = createStsCert(response.body!!.inputStream, keyPassword, storePassword)
            StsProvisioningResult(
                cert = cert,
                cn = cn,
                renewAt = cert.notAfter - Duration.ofDays(renewBeforeDays)
            )
        } catch (e: Exception) {
            throw ProvisioningException(
                "Failed provisioning sts certificate with commonName=$cn ${e.message}",
                e
            )
        }
    }

    companion object {

        fun createStsCert(
            body: InputStream,
            keyPassword: String,
            storePassword: String
        ): StsCertificate {

            val keyStore = KeyStore.getInstance("JKS").apply {
                this.load(body, storePassword.toCharArray())
            }

            val x509 = keyStore.getCertificate("ca") as X509Certificate

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
                keyPassword = keyPassword,
                notAfter = x509.notAfter.toInstant()

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
