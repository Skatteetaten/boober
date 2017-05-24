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
import no.skatteetaten.aurora.boober.model.DeployCommand
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.service.AuroraConfigHelperKt
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import no.skatteetaten.aurora.boober.service.OpenShiftObjectGenerator
import no.skatteetaten.aurora.boober.service.OpenShiftTemplateProcessor
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import spock.lang.Specification
import spock.mock.DetachedMockFactory

@SpringBootTest(classes = [
    no.skatteetaten.aurora.boober.Configuration,
    SetupFacade,
    AuroraConfigService,
    OpenShiftObjectGenerator,
    OpenShiftTemplateProcessor,
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
  public static final String APP_NAME = "verify-ebs-users"
  final DeployCommand aid = new DeployCommand(ENV_NAME, APP_NAME)

  def setup() {
    userDetailsProvider.getAuthenticatedUser() >> new User("test", "test", "Test User")
    openShiftClient.isValidUser(_) >> true
    openShiftClient.isValidGroup(_) >> true
    openShiftClient.applyMany(_, _) >> []
  }

  def createOpenShiftResponse(String kind, OperationType operationType, int prevVersion, int currVersion) {
    def previous = mapper.convertValue(["metadata": ["resourceVersion": prevVersion]], JsonNode.class)
    def payload = Mock(JsonNode)
    def response = mapper.convertValue(["metadata": ["resourceVersion": currVersion]], JsonNode.class)

    return new OpenShiftResponse(kind, operationType, previous, payload, response)
  }

  def "Should setup process for application"() {
    def processAid = new DeployCommand("booberdev", "tvinn")

    given:
      def templateResult = this.getClass().getResource("/openshift-objects/atomhopper-new.json")
      JsonNode jsonResult = mapper.readTree(templateResult)
      resourceClient.post("processedtemplate", null, _, _) >>
          new ResponseEntity<JsonNode>(jsonResult, HttpStatus.OK)

      def auroraConfig = AuroraConfigHelperKt.auroraConfigSamples

    when:
      def result = setupFacade.executeSetup(auroraConfig, [processAid])

    then:
      result.size() == 1
      result.get(0).auroraDc.type == TemplateType.localTemplate
  }

  def "Should setup development for application"() {
    given:
      def auroraConfig = AuroraConfigHelperKt.auroraConfigSamples

    when:
      def result = setupFacade.executeSetup(auroraConfig, [aid])

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
      def imagestream = createOpenShiftResponse("imagestream", OperationType.CREATED, 1, 1)

    when:
      def result = setupFacade.generateRedeployResource([imagestream], templateType, name)

    then:
      result == null
  }

  def "Should setup deploy for application"() {
    given:
      def consoleAid = new DeployCommand(ENV_NAME, "console")
      def auroraConfig = AuroraConfigHelperKt.auroraConfigSamples

    when:
      def result = setupFacade.executeSetup(auroraConfig, [consoleAid])

    then:
      result.size() == 1
      result.get(0).auroraDc.type == deploy
  }
}
