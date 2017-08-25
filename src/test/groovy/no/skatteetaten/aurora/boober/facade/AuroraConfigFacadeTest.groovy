package no.skatteetaten.aurora.boober.facade

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

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
import no.skatteetaten.aurora.boober.service.internal.AuroraVersioningException
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig
import no.skatteetaten.aurora.boober.service.openshift.ServiceAccountTokenProvider
import no.skatteetaten.aurora.boober.service.openshift.UserDetailsTokenProvider
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
    ObjectMapper,
    Config,
    OpenShiftResourceClientConfig,
    UserDetailsTokenProvider
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
    ServiceAccountTokenProvider tokenProvider() {
      factory.Mock(ServiceAccountTokenProvider)
    }

    @Bean
    UserDetailsProvider userDetailsProvider() {
      factory.Mock(UserDetailsProvider)
    }
  }

  @Autowired
  ObjectMapper mapper

  @Autowired
  AuroraConfigFacade service

  @Autowired
  UserDetailsProvider userDetailsProvider

  @Autowired
  GitService gitService

  private void createRepoAndSaveFiles(String affiliation, AuroraConfig auroraConfig) {
    GitServiceHelperKt.createInitRepo(affiliation)
    userDetailsProvider.authenticatedUser >> new User("test", "", "Test Foo")
    service.saveAuroraConfig(affiliation, auroraConfig, false)
  }

  private AuroraConfig getAuroraConfigFromGit(String affiliation, boolean decryptSecrets) {

    def git = gitService.checkoutRepoForAffiliation(affiliation)
    def files = gitService.getAllFilesInRepo(git)
    def auroraConfig = service.createAuroraConfigFromFiles(files, "aos")
    gitService.closeRepository(git)

    return auroraConfig
  }

  def "Should not update one file in AuroraConfig if version is wrong"() {
    given:
      def auroraConfig = AuroraConfigHelperKt.createAuroraConfig(aid)
      createRepoAndSaveFiles("aos", auroraConfig)


    when:
      def fileToChange = "secrettest/aos-simple.json"

      def newFile = mapper.convertValue([], JsonNode.class)

      service.updateAuroraConfigFile("aos", fileToChange, newFile, "wrong version", true)
    then:

      def e = thrown(AuroraVersioningException)
      e.errors.size() == 1

  }

  def "Should update one file in AuroraConfig"() {
    given:
      def auroraConfig = AuroraConfigHelperKt.createAuroraConfig(aid)
      createRepoAndSaveFiles("aos", auroraConfig)

    when:
      def storedConfig = service.findAuroraConfig("aos")

      def fileToChange = "secrettest/aos-simple.json"
      def theFileToChange = storedConfig.auroraConfigFiles.find { it.name == fileToChange }

      def newFile = mapper.convertValue([], JsonNode.class)

      service.updateAuroraConfigFile("aos", fileToChange, newFile, theFileToChange.version, true)

      def git = gitService.checkoutRepoForAffiliation("aos")
      def gitLog = git.log().call().head()
      gitService.closeRepository(git)

    then:
      gitLog.authorIdent.name == "Test Foo"
      gitLog.fullMessage == "Added: 0, Modified: 1, Deleted: 0"
  }

  def "Should successfully save AuroraConfig"() {
    given:
      def auroraConfig = AuroraConfigHelperKt.createAuroraConfig(aid)
      createRepoAndSaveFiles("aos", auroraConfig)

    when:
      service.saveAuroraConfig("aos", auroraConfig, false)
      def git = gitService.checkoutRepoForAffiliation("aos")
      def gitLog = git.log().call().head()
      gitService.closeRepository(git)

    then:
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
      def patchedAuroraConfig = service.patchAuroraConfigFile("aos", filename, jsonOp, version, false)
      def git = gitService.checkoutRepoForAffiliation("aos")
      def gitLog = git.log().call().head()
      gitService.closeRepository(git)

    then:
      gitLog.fullMessage == "Added: 0, Modified: 1, Deleted: 0"
      def patchedFile = patchedAuroraConfig.auroraConfigFiles.find { it.name == filename }
      patchedFile.contents.at("/version").textValue() == "3"
  }
}
