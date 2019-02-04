package no.skatteetaten.aurora.boober.service.resourceprovisioning

import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess

import java.security.KeyStore

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.client.AutoConfigureWebClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithUserDetails
import org.springframework.test.web.client.MockRestServiceServer

import no.skatteetaten.aurora.boober.Configuration
import no.skatteetaten.aurora.boober.service.AbstractSpec
import no.skatteetaten.aurora.boober.service.SpringTestUtils
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.internal.SharedSecretReader

@WithUserDetails("aurora")
@AutoConfigureWebClient
@SpringBootTest(classes = [
    Configuration,
    SharedSecretReader,
    SpringTestUtils.SecurityMock,
    StsProvisioner,
    UserDetailsProvider,
    SpringTestUtils.SkapMockRestServiceServiceInitializer
])
class StsProvisionerTest extends AbstractSpec {

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
}
