package no.skatteetaten.aurora.boober.facade

import static no.skatteetaten.aurora.boober.model.TemplateType.deploy
import static no.skatteetaten.aurora.boober.model.TemplateType.development
import static no.skatteetaten.aurora.boober.service.openshift.OperationType.UPDATE

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
import no.skatteetaten.aurora.boober.service.EncryptionService
import no.skatteetaten.aurora.boober.service.GitService
import no.skatteetaten.aurora.boober.service.OpenShiftObjectGenerator
import no.skatteetaten.aurora.boober.service.OpenShiftTemplateProcessor
import no.skatteetaten.aurora.boober.service.SecretVaultService
import no.skatteetaten.aurora.boober.service.internal.AuroraConfigException
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType
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
    EncryptionService,
    AuroraConfigFacade,
    Config
])
class SetupFacadeTest extends Specification {

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

  def createOpenShiftResponse(String kind, OperationType operationType, int prevVersion, int currVersion) {
    def previous = mapper.convertValue(["kind": kind, "metadata": ["resourceVersion": prevVersion]], JsonNode.class)
    def payload = Mock(JsonNode)
    def response = mapper.convertValue(["kind": kind, "metadata": ["resourceVersion": currVersion]], JsonNode.class)

    return new OpenShiftResponse(new OpenshiftCommand(operationType, payload, previous, null), response)
  }

  def "Should setup process for application"() {

    def processAid = new ApplicationId("booberdev", "tvinn")
    def deployCommand = new DeployCommand(processAid)

    given:
      def templateResult = this.getClass().getResource("/samples/processedtemplate/booberdev/tvinn/atomhopper.json")
      JsonNode jsonResult = mapper.readTree(templateResult)
      resourceClient.post("processedtemplate", null, _, _) >>
          new ResponseEntity<JsonNode>(jsonResult, HttpStatus.OK)

      def auroraConfig = AuroraConfigHelperKt.auroraConfigSamples

    when:
      def result = setupFacade.createApplicationCommands(auroraConfig, [deployCommand], [:], deployId)

    then:
      result.size() == 1
      result.get(0).auroraDc.type == TemplateType.localTemplate
  }

  def "Should setup development for application"() {
    given:
      def deployCommand = new DeployCommand(aid)
      def auroraConfig = AuroraConfigHelperKt.auroraConfigSamples

    when:
      def result = setupFacade.createApplicationCommands(auroraConfig, [deployCommand], [:], deployId)

    then:
      result.size() == 1
      result.get(0).auroraDc.type == development

  }

  def "Should not create redeploy resource when ImageStream has not changed and template is development"() {
    given:
      def templateType = development
      def name = "boober"
      def imagestream = createOpenShiftResponse("imagestream", UPDATE, 1, 1)

    when:
      def result = setupFacade.generateRedeployResource([imagestream], templateType, name)

    then:
      result == null
  }

  def "Should create BuildRequest when any object has changed and template is development"() {
    given:
      def templateType = development
      def name = "boober"
      def imagestream = createOpenShiftResponse("imagestream", UPDATE, 1, 1)
      def deploymentConfig = createOpenShiftResponse("deploymentconfig", UPDATE, 1, 2)

    when:
      def result = setupFacade.generateRedeployResource([imagestream, deploymentConfig], templateType, name)

    then:
      result.get("kind").asText() == "BuildRequest"
  }

  def "Should create DeploymentRequest when no ImageStream is present and DeploymentConfig has changed and template is deploy"() {
    given:
      def templateType = deploy
      def name = "boober"
      def deploymentConfig = createOpenShiftResponse("deploymentconfig", UPDATE, 1, 2)

    when:
      def result = setupFacade.generateRedeployResource([deploymentConfig], templateType, name)

    then:
      result.get("kind").asText() == "DeploymentRequest"
  }

  def "Should not create redeploy resource if there is no ImageStream and DeploymentConfig"() {
    expect:
      def templateType = deploy
      def name = "boober"
      setupFacade.generateRedeployResource([], templateType, name) == null
  }

  def "Should create DeploymentRequest when ImageStream has not changed but OperationType is Update and template is deploy"() {
    given:
      def templateType = deploy
      def name = "boober"
      def imagestream = createOpenShiftResponse("imagestream", UPDATE, 1, 1)

    when:
      def result = setupFacade.generateRedeployResource([imagestream], templateType, name)

    then:
      result.get("kind").asText() == "DeploymentRequest"
  }

  def "Should not create redeploy resource when objects are created and template is deploy"() {
    given:
      def templateType = deploy
      def name = "boober"
      def imagestream = createOpenShiftResponse("imagestream", OperationType.CREATE, 1, 1)

    when:
      def result = setupFacade.generateRedeployResource([imagestream], templateType, name)

    then:
      result == null
  }

  def "Should setup deploy for application"() {
    given:
      def consoleAid = new ApplicationId(ENV_NAME, "console")
      def deployCommand = new DeployCommand(consoleAid)
      def auroraConfig = AuroraConfigHelperKt.auroraConfigSamples

    when:
      def result = setupFacade.createApplicationCommands(auroraConfig, [deployCommand], [:], deployId)

    then:
      result.size() == 1
      result.get(0).auroraDc.type == deploy
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
}
