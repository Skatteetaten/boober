package no.skatteetaten.aurora.boober.service

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.utils.AuroraConfigHelper
import spock.lang.Specification
import spock.mock.DetachedMockFactory

@SpringBootTest(classes = [no.skatteetaten.aurora.boober.Configuration,
    SetupService,
    AuroraDeploymentConfigService,
    OpenShiftObjectGenerator,
    Config])
class SetupServiceTest extends Specification {

  @Configuration
  static class Config {
    private DetachedMockFactory factory = new DetachedMockFactory()

    @Bean
    UserDetailsProvider userDetailsProvider() {

      factory.Mock(UserDetailsProvider)
    }

    @Bean
    OpenShiftClient openshiftClient() {
      factory.Mock(OpenShiftClient)
    }
  }

  @Autowired
  OpenShiftClient openShiftClient

  @Autowired
  UserDetailsProvider userDetailsProvider

  @Autowired
  SetupService setupService

  public static final String ENV_NAME = "booberdev"
  public static final String APP_NAME = "verify-ebs-users"
  final ApplicationId aid = new ApplicationId(ENV_NAME, APP_NAME)

  def setup() {
    userDetailsProvider.getAuthenticatedUser() >> new User("test", "test", "Test User")
    openShiftClient.isValidUser(_) >> true
    openShiftClient.isValidGroup(_) >> true
    openShiftClient.applyMany(_, _) >> []
  }

  def "Should setup development for application"() {
    given:
      def auroraConfig = AuroraConfigHelper.createAuroraConfig(aid)

    when:
      def result = setupService.executeSetup(auroraConfig, [aid], [])

    then:
      result.size() == 1
      result.get(0).auroraDc.type == TemplateType.development

  }

  def "Should setup deploy for application"() {
    given:
      def consoleAid = new ApplicationId(ENV_NAME, "console")
      def auroraConfig = AuroraConfigHelper.createAuroraConfig(consoleAid)

    when:
      def result = setupService.executeSetup(auroraConfig, [consoleAid], [])

    then:
      result.size() == 1
      result.get(0).auroraDc.type == TemplateType.deploy
  }
}
