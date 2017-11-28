package no.skatteetaten.aurora.boober.service

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.web.client.RestTemplate

import no.skatteetaten.aurora.boober.Configuration
import no.skatteetaten.aurora.boober.ServiceTypes
import no.skatteetaten.aurora.boober.TargetService
import no.skatteetaten.aurora.boober.service.internal.SharedSecretReader
import no.skatteetaten.aurora.filter.logging.AuroraHeaderFilter
import no.skatteetaten.aurora.filter.logging.RequestKorrelasjon

@RestClientTest
@SpringBootTest(
    classes = [
        Configuration,
        DatabaseSchemaProvisioner,
        SharedSecretReader,
        SpringTestUtils.AuroraMockRestServiceServiceInitializer],
    properties = [
        'aurora.token.value=token'
    ]
)

class AuroraRestTemplateTest extends AbstractSpec {

  @Autowired
  MockRestServiceServer mockServer

  @Autowired
  @TargetService(ServiceTypes.AURORA)
  RestTemplate auroraRestTemplate

  def "Verify auroraRestTemplate sets and uses required headers"() {

    given:
      new AuroraHeaderFilter() {
        {
          assertKorrelasjonsIdIsSet()
        }
      }

      def url = "http://example.com/api/"
      mockServer.expect(requestTo(url))
          .andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
          .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer aurora-token token"))
          .andExpect(header(AuroraHeaderFilter.KORRELASJONS_ID, RequestKorrelasjon.getId()))
          .andRespond(withSuccess('''{"success": true}''', MediaType.APPLICATION_JSON))
    when:
      def response = auroraRestTemplate.getForObject(url, Map)

    then:
      response.success
  }

}
