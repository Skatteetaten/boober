package no.skatteetaten.aurora.boober.service

import org.springframework.beans.factory.annotation.Autowired

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import no.skatteetaten.aurora.boober.service.vault.VaultService

class DeployServiceTest extends AbstractMockedOpenShiftSpecification {

  @Autowired
  VaultService vaultService

  @Autowired
  OpenShiftClient openShiftClient

  @Autowired
  DeployService deployService

  @Autowired
  DeployLogService deployLogService

  @Autowired
  DockerService dockerService

  @Autowired
  ObjectMapper mapper

  public static final String ENV_NAME = "booberdev"
  public static final String APP_NAME = "aos-simple"
  def affiliation = "aos"

  final ApplicationId aid = new ApplicationId(ENV_NAME, APP_NAME)

  def setup() {

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
}
