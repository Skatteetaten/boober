package no.skatteetaten.aurora.boober.service

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.utils.AuroraConfigHelper
import spock.lang.Specification
import spock.mock.DetachedMockFactory

@SpringBootTest(classes = [
    no.skatteetaten.aurora.boober.Configuration,
    GitService,
    AuroraConfigService,
    EncryptionService,
    ObjectMapper,
    Config
], properties = [
  "boober.git.url=/tmp/boober-test",
  "boober.git.checkoutPath=/tmp/boober",
  "boober.git.username=",
  "boober.git.password="
])
class GitServiceTest extends Specification{
  @Configuration
  static class Config {
    private DetachedMockFactory factory = new DetachedMockFactory()

    @Bean
    AuroraDeploymentConfigService auroraDeploymentConfigService() {
      factory.Mock(AuroraDeploymentConfigService)
    }

    @Bean
    OpenShiftClient openShiftClient() {
      factory.Mock(OpenShiftClient)
    }

    @Bean
    UserDetailsProvider userDetailsProvider() {
      factory.Mock(UserDetailsProvider)
    }
  }

  @Autowired
  GitService gitService

  @Autowired
  AuroraConfigService auroraConfigService

  @Autowired
  UserDetailsProvider userDetailsProvider

  public static final String ENV_NAME = "booberdev"
  public static final String APP_NAME = "verify-ebs-users"
  final ApplicationId aid = new ApplicationId(ENV_NAME, APP_NAME)

  private void createRepoAndSaveFiles(String affiliation) {
    GitServiceHelperKt.createInitRepo(affiliation)
    userDetailsProvider.authenticatedUser >> new User("test", "", "Test Foo")
    def auroraConfig = AuroraConfigHelper.createAuroraConfig(aid)
    auroraConfigService.saveAuroraConfig(affiliation, auroraConfig)
  }

  def "Should checkout repo"() {
    given:
      createRepoAndSaveFiles("aos")

    when:
      def git = gitService.checkoutRepoForAffiliation("aos")
      def files = gitService.getAllFilesInRepo(git)
      gitService.closeRepository(git)

    then:
      files.containsKey("about.json")
      files.containsKey("verify-ebs-users.json")
      files.containsKey("booberdev/about.json")
      files.containsKey("booberdev/verify-ebs-users.json")
  }
}
