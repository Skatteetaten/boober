package no.skatteetaten.aurora.boober.facade

import static no.skatteetaten.aurora.boober.model.TemplateType.deploy
import static no.skatteetaten.aurora.boober.model.TemplateType.development
import static no.skatteetaten.aurora.boober.service.openshift.OperationType.UPDATE

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import no.skatteetaten.aurora.boober.service.DockerService
import no.skatteetaten.aurora.boober.service.EncryptionService
import no.skatteetaten.aurora.boober.service.GitService
import no.skatteetaten.aurora.boober.service.OpenShiftObjectGenerator
import no.skatteetaten.aurora.boober.service.OpenShiftTemplateProcessor
import no.skatteetaten.aurora.boober.service.SecretVaultService
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import no.skatteetaten.aurora.boober.service.openshift.ServiceAccountTokenProvider
import no.skatteetaten.aurora.boober.service.openshift.TokenProvider
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
    Config,
    OpenShiftResourceClientConfig,
    UserDetailsTokenProvider
])
class SetupFacadeGenerateDeployResourceTest extends Specification {

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

    // @Bean
    OpenShiftResourceClient resourceClient() {
      factory.Mock(OpenShiftResourceClient)
    }

    @Bean
    ServiceAccountTokenProvider serviceTokenProvider() {
      factory.Mock(ServiceAccountTokenProvider)
    }

    @Bean
    TokenProvider tokenProvider() {
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

  def createOpenShiftResponse(String kind, OperationType operationType, int prevVersion, int currVersion) {
    def previous = mapper.
        convertValue(["kind": kind, "metadata": ["labels": ["releasedVersion": prevVersion]]], JsonNode.class)
    def payload = Mock(JsonNode)
    def response = mapper.
        convertValue(["kind": kind, "metadata": ["labels": ["releasedVersion": currVersion]]], JsonNode.class)

    return new OpenShiftResponse(new OpenshiftCommand(operationType, payload, previous, null), response)
  }

  def "Should  create redeploy resource when development flow"() {
    given:
      def templateType = development
      def name = "boober"
      def imagestream = createOpenShiftResponse("imagestream", UPDATE, 1, 1)

    when:
      def result = setupFacade.generateRedeployResource([imagestream], templateType, name)

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
      imagestream.command

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

}
