package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig.TokenSource.API_USER
import static no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig.TokenSource.SERVICE_ACCOUNT

import org.spockframework.mock.MockNature
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Metrics
import no.skatteetaten.aurora.AuroraMetrics
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.service.internal.SharedSecretReader
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseSchemaProvisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.ExternalResourceProvisioner
import no.skatteetaten.aurora.boober.utils.JsonNodeUtilsKt
import spock.mock.DetachedMockFactory

@SpringBootTest(classes = [
    no.skatteetaten.aurora.boober.Configuration,
    DeployService,
    OpenShiftObjectGenerator,
    OpenShiftTemplateProcessor,
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

  @Autowired
  AuroraConfigService auroraConfigService

  @Autowired
  UserDetailsProvider userDetailsProvider

  def affiliation = "aos"

  def setup() {

    userDetailsProvider.getAuthenticatedUser() >> new User("aurora", "-", "-", [])

    resourceClientSA.put(_, _, _, _) >> { new ResponseEntity<JsonNode>(it[3], HttpStatus.OK) }
    resourceClientUser.put(_, _, _, _) >> { new ResponseEntity<JsonNode>(it[3], HttpStatus.OK) }
    resourceClientSA.post(_, _, _, _) >> { new ResponseEntity<JsonNode>(it[3], HttpStatus.OK) }
    resourceClientUser.post(_, _, _, _) >> { new ResponseEntity<JsonNode>(it[3], HttpStatus.OK) }

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
    given:
      def aid = ApplicationId.aid("imagestreamtest", "reference")
      def namespaceName = "$affiliation-$aid.environment"

      def namespaceJson = mapper.convertValue(["kind": "namespace", "metadata": [name: namespaceName, "labels": []]], JsonNode)
      def roleBinding = mapper.convertValue([kind: "rolebinding"], JsonNode)
      resourceClientSA.get(_ as String, _ as HttpHeaders, _ as Boolean) >> { new ResponseEntity<JsonNode>(mapper.convertValue([status: [phase: "Active"]], JsonNode), HttpStatus.OK) }
      resourceClientSA.get("namespace", "", namespaceName, _) >> new ResponseEntity<JsonNode>(namespaceJson, HttpStatus.OK)
      resourceClientUser.get("rolebinding", namespaceName, "admin", _) >> new ResponseEntity<JsonNode>(roleBinding, HttpStatus.OK)
      resourceClientUser.get("deploymentconfig", namespaceName, aid.application, _) >> {
        new ResponseEntity<JsonNode>(loadJsonResource("$affiliation-$aid.environment-$aid.application-deploymentconfig.json"), HttpStatus.OK)
      }
      resourceClientUser.post("imagestreamimport", namespaceName, aid.application, _) >> new ResponseEntity<JsonNode>(loadJsonResource("$affiliation-$aid.environment-$aid.application-imagestreamimport.json"), HttpStatus.OK)

      def auroraConfig = AuroraConfig.
          fromFolder("/home/k77319/projects/github/boober/src/test/resources/samples/config")
      def deploymentSpec = auroraConfig.getAuroraDeploymentSpec(aid)
      auroraConfigService.createValidatedAuroraDeploymentSpecs(affiliation, _, _, _) >> [deploymentSpec]

    when:
      List<AuroraDeployResult> deployResults = deployService.executeDeploy(affiliation, [aid], [], true)

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

  def "Should delete and create route"() {
    when:
      List<AuroraDeployResult> deployResults = deployService.
          executeDeploy(affiliation, [new ApplicationId(ENV_NAME, APP_NAME)], [], true)

    then:
      def result = deployResults[0]
      //TODO: This way of testing is very nice!
      def resultSentences = result.openShiftResponses.collect {
        def name = JsonNodeUtilsKt.getOpenshiftName(it.command.payload)
        def kind = JsonNodeUtilsKt.getOpenshiftKind(it.command.payload)
        "${it.command.operationType} $kind $name".trim()
      }
      resultSentences ==
          ['CREATE projectrequest aos-mounts',
           'UPDATE namespace aos-mounts',
           'CREATE rolebinding admin',
           'CREATE deploymentconfig aos-simple',
           'CREATE service aos-simple',
           'CREATE imagestream aos-simple',
           'CREATE buildconfig aos-simple',
           'CREATE configmap aos-simple',
           'DELETE route aos-simple',
           'CREATE route aos-simple',
           'DELETE route aos-simple-bar',
           'CREATE route aos-simple-bar',
          ]
  }
}
