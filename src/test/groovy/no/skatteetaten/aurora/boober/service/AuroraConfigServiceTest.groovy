package no.skatteetaten.aurora.boober.service

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.DeployCommand
import no.skatteetaten.aurora.boober.service.internal.AuroraConfigException
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import spock.lang.Specification
import spock.mock.DetachedMockFactory

@SpringBootTest(classes = [
    no.skatteetaten.aurora.boober.Configuration,
    AuroraConfigService,
    Config
])
class AuroraConfigServiceTest extends Specification {

  @Configuration
  static class Config {
    private DetachedMockFactory factory = new DetachedMockFactory()

    @Bean
    OpenShiftClient openShiftClient() {
      factory.Mock(OpenShiftClient)
    }

  }

  @Autowired
  AuroraConfigService service

  def "Should get error if creating mapper for auroraConfig with missing schemaVersion "() {
    given:
      def aid = new ApplicationId("error", "no-schema")
      def auroraConfig = AuroraConfigHelperKt.createAuroraConfig(aid)

    when:
      service.createAuroraDcs(auroraConfig, [new DeployCommand(aid)], [:])

    then:
      def e = thrown(AuroraConfigException)

      e.errors[0].messages[0].message == "schemaVersion is not set"
  }
}
