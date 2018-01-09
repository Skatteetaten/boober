package no.skatteetaten.aurora.boober.service

import org.eclipse.jgit.api.Git
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType

class DeployServiceFromGitTest extends AbstractMockedOpenShiftSpecification {

  @Autowired
  VaultService vaultService

  @Autowired
  OpenShiftClient openShiftClient

  @Autowired
  DeployService deployService

  @Autowired
  DeployLogService deployLogService

  @Autowired
  GitService gitService

  @Autowired
  DockerService dockerService

  @Autowired
  ObjectMapper mapper

  public static final String ENV_NAME = "booberdev"
  public static final String APP_NAME = "aos-simple"
  def affiliation = "aos"

  final ApplicationId aid = new ApplicationId(ENV_NAME, APP_NAME)

  Git git

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

    git = gitService.checkoutRepository(affiliation)
  }

  def cleanup() {
    git.close()
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

  def "Should perform release and mark it"() {
    when:
      deployService.executeDeploy(affiliation, [new ApplicationId(ENV_NAME, APP_NAME)], [], true)

    then:

      def history = gitService.getTagHistory(git)
      history.size() == 1
      def revTag = history[0]

      revTag.taggerIdent != null
      revTag.fullMessage.startsWith("""{"deployId":""")
      revTag.tagName.startsWith("DEPLOY/utv.aos-booberdev.aos-simple/")
  }

  def "Should perform two releases and get deploy history"() {
    when:
      deployService.
          executeDeploy(affiliation, [new ApplicationId(ENV_NAME, APP_NAME), new ApplicationId(ENV_NAME, "sprocket")],
              [], true)

    then:
      def tags = deployLogService.deployHistory(affiliation)
      tags.size() == 2
      def revTag = tags[0]

      revTag.ident != null
      revTag.result.get("deployId") != null

      def revTag2 = tags[1]

      revTag2.ident != null
      revTag2.result.get("deployId") != null
  }

  def "Should perform release and tag in docker repo"() {
    given:
      1 * dockerService.tag(_) >> {
        new TagResult(it[0],
            new ResponseEntity<JsonNode>(mapper.convertValue(["foo": "foo"], JsonNode.class), HttpStatus.OK), true)
      }

    when:
      def result = deployService.executeDeploy(affiliation, [new ApplicationId("release", "aos-simple")], [], true)

    then:
      result.size() == 1
      result[0].tagResponse.success

  }

}
