package no.skatteetaten.aurora.boober.facade

import static no.skatteetaten.aurora.boober.model.TemplateType.deploy
import static no.skatteetaten.aurora.boober.model.TemplateType.development

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.DeployCommand
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.service.AuroraConfigHelperKt
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import no.skatteetaten.aurora.boober.service.DockerService
import no.skatteetaten.aurora.boober.service.EncryptionService
import no.skatteetaten.aurora.boober.service.GitService
import no.skatteetaten.aurora.boober.service.OpenShiftObjectGenerator
import no.skatteetaten.aurora.boober.service.OpenShiftTemplateProcessor
import no.skatteetaten.aurora.boober.service.SecretVaultService
import no.skatteetaten.aurora.boober.service.internal.AuroraConfigException
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
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
    SecretVaultService,
    SetupFacade,
    AuroraConfigService,
    OpenShiftObjectGenerator,
    OpenShiftTemplateProcessor,
    GitService,
    VaultFacade,
    DockerService,
    EncryptionService,
    AuroraConfigFacade,
    Config
])
class SetupFacadeCreateCommandTest extends Specification {

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

    @Bean
    OpenShiftResourceClient resourceClient() {
      factory.Mock(OpenShiftResourceClient)
    }

    @Bean
    ServiceAccountTokenProvider tokenProvider() {
      factory.Mock(ServiceAccountTokenProvider)
    }

  }

  @Autowired
  OpenShiftClient openShiftClient

  @Autowired
  OpenShiftResourceClient resourceClient

  @Autowired
  UserDetailsProvider userDetailsProvider

  @Autowired
  SetupFacade setupFacade

  @Autowired
  ObjectMapper mapper

  public static final String ENV_NAME = "booberdev"
  public static final String APP_NAME = "aos-simple"
  final ApplicationId aid = new ApplicationId(ENV_NAME, APP_NAME)

  def deployId = "123"

  def setup() {
    userDetailsProvider.getAuthenticatedUser() >> new User("test", "test", "Test User")
    openShiftClient.isValidUser(_) >> true
    openShiftClient.isValidGroup(_) >> true
    openShiftClient.prepare(_, _) >> { new OpenshiftCommand(OperationType.CREATE, it[1]) }
    openShiftClient.performOpenShiftCommand(_, _) >> {
      OpenshiftCommand cmd = it[1]
      new OpenShiftResponse(cmd, cmd.payload)
    }
  }

  def "Should setup deploy for application"() {
    given:
      def consoleAid = new ApplicationId(ENV_NAME, "console")
      def deployCommand = new DeployCommand(consoleAid)
      def auroraConfig = AuroraConfigHelperKt.auroraConfigSamples

    when:
      def result = setupFacade.createApplicationCommands(auroraConfig, [deployCommand], [:], deployId)

    then:
    def e = thrown(AuroraConfigException)

    result.size() == 1
      result.get(0).auroraDc.type == deploy
      result.get(0).tagCommand == null
  }

  def "Should get error when using vault you have no permission for"() {
    given:
      def deployCommand = new DeployCommand(new ApplicationId("secrettest", "aos-simple"))
      def auroraConfig = AuroraConfigHelperKt.auroraConfigSamples

    when:
      def result = setupFacade.createApplicationCommands(auroraConfig, [deployCommand], [:], deployId)

    then:
      def e = thrown(AuroraConfigException)
      e.errors[0].messages[0].message == "No secret vault named=foo, or you do not have permission to use it."

  }

  def "Should setup deploy for application with releaseTo"() {
    given:
      def consoleAid = new ApplicationId("release", "aos-simple")
      def deployCommand = new DeployCommand(consoleAid)
      def auroraConfig = AuroraConfigHelperKt.auroraConfigSamples

    when:
      def result = setupFacade.createApplicationCommands(auroraConfig, [deployCommand], [:], deployId)

    then:
      result.size() == 1
      def cmd = result[0]
      cmd.auroraDc.type == deploy
      cmd.tagCommand.from == "1.0.3-b1.1.0-wingnut-1.0.0"
      cmd.tagCommand.to == "ref"
      cmd.tagCommand.name == "ske_aurora_openshift/aos-simple"

  }
}
