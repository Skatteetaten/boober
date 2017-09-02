package no.skatteetaten.aurora.boober.facade

import java.nio.charset.Charset

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException

import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.controller.internal.SetupParams
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.service.AuroraConfigHelperKt
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import no.skatteetaten.aurora.boober.service.DockerService
import no.skatteetaten.aurora.boober.service.EncryptionService
import no.skatteetaten.aurora.boober.service.GitService
import no.skatteetaten.aurora.boober.service.GitServiceHelperKt
import no.skatteetaten.aurora.boober.service.OpenShiftObjectGenerator
import no.skatteetaten.aurora.boober.service.OpenShiftTemplateProcessor
import no.skatteetaten.aurora.boober.service.SecretVaultService
import no.skatteetaten.aurora.boober.service.internal.OpenShiftException
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import no.skatteetaten.aurora.boober.service.openshift.ServiceAccountTokenProvider
import no.skatteetaten.aurora.boober.service.openshift.UserDetailsTokenProvider
import spock.lang.Specification
import spock.mock.DetachedMockFactory

@SpringBootTest(classes = [
    no.skatteetaten.aurora.boober.Configuration,
    SetupFacade,
    AuroraConfigService,
    OpenShiftObjectGenerator,
    OpenShiftTemplateProcessor,
    GitService,
    SecretVaultService,
    EncryptionService,
    AuroraConfigFacade,
    VaultFacade,
    ObjectMapper,
    Config,
    OpenShiftResourceClientConfig,
    UserDetailsTokenProvider
]
    , properties = [
        "boober.git.urlPattern=/tmp/boober-test/%s",
        "boober.git.checkoutPath=/tmp/boober",
        "boober.git.username=",
        "boober.git.password="
    ])
class SetupFacadeFromGitFailTest extends Specification {

  @Configuration
  static class Config {
    private DetachedMockFactory factory = new DetachedMockFactory()

    @Bean
    UserDetailsProvider userDetailsProvider() {

      factory.Mock(UserDetailsProvider)
    }

    @Bean
    ServiceAccountTokenProvider tokenProvider() {
      factory.Mock(ServiceAccountTokenProvider)
    }

    @Bean
    OpenShiftClient openshiftClient() {
      factory.Mock(OpenShiftClient)
    }

    @Bean
    DockerService dockerService() {
      factory.Mock(DockerService)
    }
  }

  @Autowired
  VaultFacade vaultFacade

  @Autowired
  OpenShiftClient openShiftClient

  @Autowired
  UserDetailsProvider userDetailsProvider

  @Autowired
  SetupFacade setupFacade

  @Autowired
  GitService gitService

  @Autowired
  AuroraConfigFacade configFacade

  @Autowired
  DockerService dockerService

  @Autowired
  ObjectMapper mapper

  public static final String ENV_NAME = "booberdev"
  public static final String APP_NAME = "aos-simple"
  def affiliation = "aos"

  final ApplicationId aid = new ApplicationId(ENV_NAME, APP_NAME)

  def setup() {
    userDetailsProvider.getAuthenticatedUser() >> new User("test", "test", "Test User")
    openShiftClient.isValidUser(_) >> true
    openShiftClient.isValidGroup(_) >> true
    openShiftClient.prepare(_, _) >> { new OpenshiftCommand(OperationType.CREATE, it[1]) }
    openShiftClient.performOpenShiftCommand(_, _) >> {
      def cmd = it[0]
      def body = '''{ "response": "failed"}'''.bytes
      def cause = new HttpClientErrorException(HttpStatus.BAD_REQUEST, "Bad Request", body, Charset.defaultCharset())
      def error = new OpenShiftException("Error saving url", cause)
      new OpenShiftResponse(cmd, cmd.payload, false, error.message)
    }
    openShiftClient.createOpenshiftDeleteCommands(_, _, _, _) >> []
    openShiftClient.hasUserAccess(_, _) >> true

  }

  private void createRepoAndSaveFiles(String affiliation, AuroraConfig auroraConfig) {
    GitServiceHelperKt.createInitRepo(affiliation)
    configFacade.saveAuroraConfig(affiliation, auroraConfig, false)
  }

  def "Should perform release that fails and mark it as failed"() {
    given:
      GitServiceHelperKt.createInitRepo(affiliation)
      def auroraConfig = AuroraConfigHelperKt.createAuroraConfig(aid, "aos")
      createRepoAndSaveFiles("aos", auroraConfig)

    when:

      setupFacade.executeSetup(affiliation, new SetupParams([ENV_NAME], [APP_NAME], [], true))

    then:
      def git = gitService.checkoutRepoForAffiliation(affiliation)

      def history = gitService.tagHistory(git)
      history.size() == 1
      def revTag = history[0]

      revTag.taggerIdent != null
      revTag.fullMessage.startsWith("""{"deployId":""")
      revTag.tagName.startsWith("FAILED/aos-booberdev.aos-simple/")
      gitService.closeRepository(git)

  }

}
