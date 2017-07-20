package no.skatteetaten.aurora.boober.facade

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.service.AuroraConfigHelperKt
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import no.skatteetaten.aurora.boober.service.EncryptionService
import no.skatteetaten.aurora.boober.service.GitService
import no.skatteetaten.aurora.boober.service.GitServiceHelperKt
import no.skatteetaten.aurora.boober.service.SecretVaultService
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import spock.lang.Specification
import spock.mock.DetachedMockFactory

@SpringBootTest(classes = [
    no.skatteetaten.aurora.boober.Configuration,
    AuroraConfigFacade,
    GitService,
    OpenShiftClient,
    EncryptionService,
    OpenShiftResourceClient,
    SecretVaultService,
    Config
], properties = [
    "boober.git.urlPattern=/tmp/boober-test/%s",
    "boober.git.checkoutPath=/tmp/boober",
    "boober.git.username=",
    "boober.git.password="
])
class AuroraConfigFacadeTest extends Specification {

  public static final String ENV_NAME = "secrettest"
  public static final String APP_NAME = "aos-simple"
  final ApplicationId aid = new ApplicationId(ENV_NAME, APP_NAME)

  @Configuration
  static class Config {
    private DetachedMockFactory factory = new DetachedMockFactory()

    @Bean
    AuroraConfigService auroraDeploymentConfigService() {
      factory.Mock(AuroraConfigService)
    }

    @Bean
    UserDetailsProvider userDetailsProvider() {
      factory.Mock(UserDetailsProvider)
    }
  }

  @Autowired
  AuroraConfigFacade service

  @Autowired
  UserDetailsProvider userDetailsProvider

  @Autowired
  GitService gitService

  private void createRepoAndSaveFiles(String affiliation, AuroraConfig auroraConfig) {
    GitServiceHelperKt.createInitRepo(affiliation)
    userDetailsProvider.authenticatedUser >> new User("test", "", "Test Foo")
    service.saveAuroraConfig(affiliation, auroraConfig)
  }

  private AuroraConfig getAuroraConfigFromGit(String affiliation, boolean decryptSecrets) {

    def git = gitService.checkoutRepoForAffiliation(affiliation)
    def files = gitService.getAllFilesInRepo(git)
    def auroraConfig = service.createAuroraConfigFromFiles(files, "aos")
    gitService.closeRepository(git)

    return auroraConfig
  }

  def "Should successfully save AuroraConfig"() {
    given:
      GitServiceHelperKt.createInitRepo("aos")
      def auroraConfig = AuroraConfigHelperKt.createAuroraConfig(aid)
      userDetailsProvider.authenticatedUser >> new User("foobar", "", "Foo Bar")

    when:
      service.saveAuroraConfig("aos", auroraConfig)
      def git = gitService.checkoutRepoForAffiliation("aos")
      def gitLog = git.log().call().head()
      gitService.closeRepository(git)

    then:
      gitLog.authorIdent.name == "Foo Bar"
      gitLog.fullMessage == "Added: 4, Modified: 0, Deleted: 0"
  }

  def "Should patch AuroraConfigFile and push changes to git"() {
    given:
      def auroraConfig = AuroraConfigHelperKt.createAuroraConfig(aid, "aos")
      createRepoAndSaveFiles("aos", auroraConfig)
      def gitAuroraConfig = getAuroraConfigFromGit("aos", false)

      def jsonOp = """[{
  "op": "replace",
  "path": "/version",
  "value": "3"
}]
"""

    when:
      def filename = "${aid.environment}/${aid.application}.json"

      def version = gitAuroraConfig.auroraConfigFiles.find { it.name == filename }.version
      def patchedAuroraConfig = service.patchAuroraConfigFile("aos", filename, jsonOp, version)
      def git = gitService.checkoutRepoForAffiliation("aos")
      def gitLog = git.log().call().head()
      gitService.closeRepository(git)

    then:
      gitLog.fullMessage == "Added: 0, Modified: 1, Deleted: 0"
      def patchedFile = patchedAuroraConfig.auroraConfigFiles.find { it.name == filename }
      patchedFile.contents.at("/version").textValue() == "3"
  }
}
