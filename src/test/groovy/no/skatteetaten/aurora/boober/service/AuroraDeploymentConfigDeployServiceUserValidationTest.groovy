package no.skatteetaten.aurora.boober.service

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.DeployCommand
import no.skatteetaten.aurora.boober.service.internal.AuroraConfigException
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import spock.lang.Specification
import spock.mock.DetachedMockFactory

@SpringBootTest(classes = [
    no.skatteetaten.aurora.boober.Configuration,
    AuroraConfigService,
    OpenShiftResourceClient,
    Config
])
class AuroraDeploymentConfigDeployServiceUserValidationTest extends Specification {

  public static final String ENV_NAME = "booberdev"
  public static final String APP_NAME = "aos-simple"
  final ApplicationId aid = new ApplicationId(ENV_NAME, APP_NAME)

  @Configuration
  static class Config {
    private DetachedMockFactory factory = new DetachedMockFactory()

    @Bean
    UserDetailsProvider userDetailsProvider() {

      factory.Stub(UserDetailsProvider)
    }

    // @Bean
    OpenShiftClient openShiftClient() {
      factory.Mock(OpenShiftClient)
    }

    @Bean
    GitService gitService() {
      factory.Mock(GitService)
    }
  }

  @Autowired
  UserDetailsProvider userDetailsProvider

  @Autowired
  OpenShiftClient openShiftClient

  @Autowired
  AuroraConfigService auroraDeploymentConfigService

  def setup() {
    userDetailsProvider.getAuthenticatedUser() >> new User("test", "test", "Test User")

    openShiftClient.prepareCommands(_, _) >> []
  }

  def "Should get error if user is not valid"() {

    given:
      def deployCommand = new DeployCommand(aid)
      openShiftClient.isValidUser("foo") >> false
      openShiftClient.isValidGroup(_) >> true

      AuroraConfig auroraConfig = AuroraConfigHelperKt.auroraConfigSamples

    when:
      auroraDeploymentConfigService.createAuroraDcs(auroraConfig, [deployCommand], [:])

    then:
      def e = thrown(AuroraConfigException)
      e.errors.size() == 1
      e.errors[0].messages[0].message == "The following users are not valid=foo"
      e.errors[0].messages[0].field.source == "about.json"

  }

  def "Should get error if group is not valid"() {

    given:
      def deployCommand = new DeployCommand(aid)
      openShiftClient.isValidUser(_) >> true
      openShiftClient.isValidGroup(_) >> false

      AuroraConfig auroraConfig = AuroraConfigHelperKt.auroraConfigSamples

    when:
      auroraDeploymentConfigService.createAuroraDcs(auroraConfig, [deployCommand], [:])

    then:
      AuroraConfigException e = thrown()
      e.errors.size() == 1
      e.errors[0].messages[0].message == "The following groups are not valid=APP_PaaS_drift, APP_PaaS_utv"
      e.errors[0].messages[0].field.source == "about.json"
  }

}

