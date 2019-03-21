package no.skatteetaten.aurora.boober.service.resourceprovisioning

import assertk.assertThat
import assertk.assertions.isNotNull
import no.skatteetaten.aurora.boober.service.openshift.execute
import no.skatteetaten.aurora.boober.utils.ResourceLoader
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.http.MediaType
import java.io.ByteArrayInputStream
import java.security.KeyStore

class StsProvisionerTest : ResourceLoader() {

    protected val mockServer = MockWebServer()

    @AfterEach
    fun tearDown() {
        mockServer.shutdown()
    }

    val client = RestTemplateBuilder().rootUri(mockServer.url("/").toString()).build()
    val provisioner = StsProvisioner(
        restTemplate = client,
        renewBeforeDays = 14,
        cluster = "utv"
    )

    val cn = "boobertest"

    @Test
    fun `should provision sts from command`() {
        val response: MockResponse = MockResponse()
            .addHeader("Content-Type", MediaType.APPLICATION_OCTET_STREAM_VALUE)
            .addHeader("key-password", "ca")
            .addHeader("store-password", "")
            .setBody(loadBufferResource("keystore.jks"))

        mockServer.execute(response) {
            val provisionResult = provisioner.generateCertificate(cn, "foo", "bar")
            assertThat(provisionResult).isNotNull()
            val keystore = KeyStore.getInstance("JKS").apply {
                this.load(ByteArrayInputStream(provisionResult.cert.keystore), "".toCharArray())
            }
            assertThat(keystore).isNotNull()

        }
    }

    /*
    public static final String SKAP_HOST = "http://localhost:8082"

    @Autowired
    MockRestServiceServer skapServer

    @Autowired
    StsProvisioner provisioner

    def cn = "boobertest"

    def "should provision sts from command"() {

        given:

        def headers = new HttpHeaders()
        headers.add("key-password", "ca")
        headers.add("store-password", "")

        skapServer.expect(requestTo("${SKAP_HOST}/certificate?cn=${cn}&cluster=utv&name=foo&namespace=bar")).
            andRespond(withSuccess(loadByteResource("keystore.jks"), MediaType.APPLICATION_OCTET_STREAM).headers(headers))

        when:
        def provisionResult = provisioner.generateCertificate(cn, "foo", "bar")

        then:
        provisionResult != null
        def keystore = KeyStore.getInstance("JKS")
        keystore.load(new ByteArrayInputStream(provisionResult.cert.keystore), "".toCharArray())
        keystore != null
    }
    */
}