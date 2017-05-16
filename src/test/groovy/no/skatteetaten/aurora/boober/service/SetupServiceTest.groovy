package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.utils.SampleFilesCollector.getQaEbsUsersSampleFilesForEnv

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

import com.fasterxml.jackson.databind.JsonNode

import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.service.internal.AuroraConfigException
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.utils.AuroraConfigHelper
import no.skatteetaten.aurora.boober.utils.SampleFilesCollector
import spock.lang.Specification
import spock.mock.DetachedMockFactory

@SpringBootTest(classes = [no.skatteetaten.aurora.boober.Configuration,
    SetupService,
    AuroraDeploymentConfigService,
    OpenShiftService,
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

  def "Should execute setup"() {
    given:
      userDetailsProvider.getAuthenticatedUser() >> new User("test", "test", "Test User")
      openShiftClient.isValidUser(_) >> true
      openShiftClient.isValidGroup(_) >> true
      openShiftClient.applyMany(_, _) >> []
      def auroraConfig = AuroraConfigHelper.createAuroraConfig(aid)

    when:
      def results = setupService.executeSetup(auroraConfig, [aid], [])

    then:
      results.size() == 1
  }
}
