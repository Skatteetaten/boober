package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.utils.SampleFilesCollector.getQaEbsUsersSampleFiles

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.model.AuroraConfig
import spock.lang.Specification
import spock.mock.DetachedMockFactory

@SpringBootTest(classes = [no.skatteetaten.aurora.boober.Configuration,
    AuroraConfigParserService,
    SetupService,
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
  SetupService setupService

  public static final String ENV_NAME = "booberdev"
  public static final String APP_NAME = "verify-ebs-users"
  final ApplicationId aid = new ApplicationId(ENV_NAME, APP_NAME)

  def "Should create aurora dc for application"() {

    given:
      setupService.openShiftService.userDetailsProvider.getAuthenticatedUser() >> new User("test", "test", "Test User")
      setupService.openShiftClient.isValidGroup(_) >> true
      setupService.openShiftClient.isValidUser(_) >> true

      Map<String, Map<String, Object>> files = getQaEbsUsersSampleFiles()
      def auroraConfig = new AuroraConfig(files, [:])

    when:
      def result = setupService.createAuroraDcForApplication(aid, auroraConfig)

    then:
      result != null
  }
}
