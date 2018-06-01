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
import no.skatteetaten.aurora.boober.model.AuroraCertificateSpec
import no.skatteetaten.aurora.boober.service.AbstractSpec
import no.skatteetaten.aurora.boober.service.ProvisioningException
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

  def command = new AuroraCertificateSpec("boobertest", "365d", "30d")

  def "should provision sts from command"() {

    given:

      def headers = new HttpHeaders()
      headers.add("key-password", "ca")
      headers.add("store-password", "")

      skapServer.expect(requestTo("${SKAP_HOST}/certificate?cn=${command.commonName}")).
          andRespond(withSuccess(loadByteResource("keystore.jks"), MediaType.APPLICATION_OCTET_STREAM).headers(headers))

    when:
      def provisionResult = provisioner.generateCertificate(command)

    then:
      provisionResult != null
      def keystore = KeyStore.getInstance("JKS")
      keystore.load(new ByteArrayInputStream(provisionResult.cert.keystore), "".toCharArray())
      keystore != null
  }

  def "should get error if invalid time specification"() {

    given:

      def illegalCommand = new AuroraCertificateSpec("boobertest", "29d", "30d")

    when:
      provisioner.generateCertificate(illegalCommand)

    then:
      def e = thrown(ProvisioningException)
      e.message ==
          "Failed provisioning sts certificate with commonName=boobertest Illegal combination ttl=29d and renewBefoew=30d. renew must be smaller then ttl."
  }
}
