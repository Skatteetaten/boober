package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig.TokenSource.API_USER
import static no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig.TokenSource.SERVICE_ACCOUNT
import static no.skatteetaten.aurora.boober.service.openshift.OperationType.CREATE
import static no.skatteetaten.aurora.boober.service.openshift.OperationType.NOOP
import static no.skatteetaten.aurora.boober.service.openshift.OperationType.UPDATE

import org.spockframework.mock.MockNature
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Metrics
import no.skatteetaten.aurora.AuroraMetrics
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraDeployEnvironment
import no.skatteetaten.aurora.boober.model.Permission
import no.skatteetaten.aurora.boober.model.Permissions
import no.skatteetaten.aurora.boober.service.internal.SharedSecretReader
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseSchemaProvisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.ExternalResourceProvisioner
import spock.mock.DetachedMockFactory

@SpringBootTest(classes = [
    no.skatteetaten.aurora.boober.Configuration,
    DeployService,
    OpenShiftObjectGenerator,
    OpenShiftTemplateProcessor,
    GitServices,
    ObjectMapper,
    Config,
    AuroraMetrics,
    SharedSecretReader,
    VelocityTemplateJsonService,
    OpenShiftObjectLabelService,
    RedeployService,
    OpenShiftClient
])
class DeployServiceTest extends AbstractSpec {

  @Configuration
  static class Config {
    private DetachedMockFactory factory = new DetachedMockFactory()

    @Bean
    AuroraConfigService auroraConfigService() {
      factory.Mock(AuroraConfigService)
    }

    @Bean
    MeterRegistry meterRegistry() {
      Metrics.globalRegistry
    }

    @Bean
    UserDetailsProvider userDetailsProvider() {
      factory.Mock(UserDetailsProvider)
    }

    @Bean
    DatabaseSchemaProvisioner dbClient() {
      factory.Mock(DatabaseSchemaProvisioner)
    }

    @Bean
    DockerService dockerService() {
      factory.Mock(DockerService)
    }

    @Bean
    ExternalResourceProvisioner externalResourceProvisioner() {
      factory.Mock(ExternalResourceProvisioner)
    }

    @Bean
    @OpenShiftResourceClientConfig.ClientType(API_USER)
    @Primary
    OpenShiftResourceClient resourceClient() {

      factory.createMock("resourceClientUser", OpenShiftResourceClient, MockNature.MOCK, [:])
    }

    @Bean
    @OpenShiftResourceClientConfig.ClientType(SERVICE_ACCOUNT)
    OpenShiftResourceClient resourceClientSA() {

      factory.createMock("resourceClientSA", OpenShiftResourceClient, MockNature.MOCK, [:])
    }

    @Bean
    DeployLogService deployLogService() {
      factory.Mock(DeployLogService)
    }
  }

  @Autowired
  DeployService deployService

  @Autowired
  @OpenShiftResourceClientConfig.ClientType(SERVICE_ACCOUNT)
  OpenShiftResourceClient resourceClientSA

  @Autowired
  @OpenShiftResourceClientConfig.ClientType(API_USER)
  OpenShiftResourceClient resourceClientUser

  @Autowired
  ObjectMapper mapper

  public static final String ENV_NAME = "booberdev"
  public static final String APP_NAME = "aos-simple"
  def affiliation = "aos"

  final ApplicationId aid = new ApplicationId(ENV_NAME, APP_NAME)

  def setup() {

    def name = "$affiliation-$ENV_NAME" as String
    def namespaceJson = mapper.convertValue(["kind": "namespace", "metadata": [name: name, "labels": []]], JsonNode)
    resourceClientSA.get("namespace", "", name, _) >> new ResponseEntity<JsonNode>(namespaceJson, HttpStatus.OK)
    resourceClientSA.put(_, _, _, _) >> new ResponseEntity<JsonNode>(mapper.convertValue([:], JsonNode), HttpStatus.OK)
    resourceClientSA.post(_, _, _, _) >> new ResponseEntity<JsonNode>(mapper.convertValue([:], JsonNode), HttpStatus.OK)
    resourceClientUser.post(_, _, _, _) >> new ResponseEntity<JsonNode>(mapper.convertValue([:], JsonNode), HttpStatus.OK)

/*
    def resourceClient = Mock(OpenShiftResourceClient)
    openShiftClient = new OpenShiftClient("", resourceClient, resourceClient, mapper)
*/
/*
    def namespaceJson = mapper.
        convertValue(["kind": "namespace", "metadata": ["labels": ["affiliation": affiliation]]], JsonNode.class)
    openShiftClient.createOpenShiftCommand(_, _, _, _) >> { new OpenshiftCommand(OperationType.CREATE, it[1]) }
    openShiftClient.createUpdateRolebindingCommand(_, _) >> {
      new OpenshiftCommand(OperationType.UPDATE, it[0], null, it[0])
    }
    openShiftClient.createUpdateNamespaceCommand(_, _) >> {
      new OpenshiftCommand(OperationType.UPDATE, namespaceJson, null, namespaceJson)
    }
    openShiftClient.performOpenShiftCommand(_, _) >> {
      def cmd = it[1]
      def namespace = it[0]

      def name = cmd.payload.at("/metadata/name").textValue()
      def kind = cmd.payload.at("/kind").textValue().toLowerCase()
      try {
        def fileName = "$namespace-${name}-${kind}.json"
        def resource = loadResource(fileName)
        new OpenShiftResponse(cmd, mapper.readTree(resource))
      } catch (Exception e) {
        new OpenShiftResponse(cmd, cmd.payload)
      }
    }
    openShiftClient.createOpenShiftDeleteCommands(_, _, _, _) >> []
*/
  }

  def "Should perform release and not generate a deployRequest if imagestream triggers new image"() {
    when:
      List<AuroraDeployResult> deployResults = deployService.
          executeDeploy(affiliation, [new ApplicationId("imagestreamtest", "reference")], [], true)

    then:
      def result = deployResults[0]
      result.openShiftResponses.size() == 8
      result.openShiftResponses[7].responseBody.at("/kind").asText() == "ImageStreamImport"
  }

  def "Should perform release and generate a imageStreamImport request"() {
    when:
      List<AuroraDeployResult> deployResults = deployService.
          executeDeploy(affiliation, [new ApplicationId(ENV_NAME, "reference")], [], true)

    then:
      def result = deployResults[0]
      result.openShiftResponses.size() == 9
      result.openShiftResponses[7].responseBody.at("/kind").asText() == "ImageStreamImport"
      result.openShiftResponses[8].responseBody.at("/kind").asText() == "DeploymentRequest"
  }

  def "Should perform release of paused env and not generate a redploy request"() {
    when:
      List<AuroraDeployResult> deployResults = deployService.
          executeDeploy(affiliation, [new ApplicationId(ENV_NAME, APP_NAME)], [], true)

    then:
      def result = deployResults[0]
      result.auroraDeploymentSpec.deploy.flags.pause
      result.openShiftResponses.size() == 9
  }

  def "When namespace does not exist we should create it and then update it appropriately"() {
    given:
      def permission = new Permission([] as Set, [] as Set)
      def permissions = new Permissions(permission, permission)
      def deployEnvironment = new AuroraDeployEnvironment(affiliation, ENV_NAME, permissions)

    when:
      def responses = deployService.prepareDeployEnvironment(deployEnvironment, false)

    then:
      responses.find { it.command.payload.get("kind").asText() == "ProjectRequest" }.command.operationType == CREATE
      responses.find { it.command.payload.get("kind").asText() == "namespace" }.command.operationType == UPDATE
  }

  def "When namespace already exists we should not try to create or update it"() {
    given:
      def permission = new Permission([] as Set, [] as Set)
      def permissions = new Permissions(permission, permission)
      def deployEnvironment = new AuroraDeployEnvironment(affiliation, ENV_NAME, permissions)

    when:
      def responses = deployService.prepareDeployEnvironment(deployEnvironment, true)

    then:
      responses.find { it.command.payload.get("kind").asText() == "ProjectRequest" }.command.operationType == NOOP
      responses.find { it.command.payload.get("kind").asText() == "namespace" }.command.operationType == NOOP
  }
}
