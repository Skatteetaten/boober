package no.skatteetaten.aurora.boober.service

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
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

    @Bean
    UserDetailsProvider userDetailsProvider() {

      factory.Stub(UserDetailsProvider)
    }

  }

  @Autowired
  UserDetailsProvider userDetailsProvider

  @Autowired
  OpenShiftClient openShiftClient

  def setup() {
    userDetailsProvider.getAuthenticatedUser() >> new User("test", "test", "Test User")
    openShiftClient.isValidUser(_) >> true
    openShiftClient.isValidGroup(_) >> true
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

  def "Should create adc from another base file"() {
    given:
      def aid = new ApplicationId("booberdev", "aos-complex")
      def auroraConfig = AuroraConfigHelperKt.createAuroraConfig(aid, "aos", "aos-simple.json")

    when:
      def adc = service.createAuroraDcs(auroraConfig, [new DeployCommand(aid)], [:])

    then:
      adc.size() == 1

  }
}
