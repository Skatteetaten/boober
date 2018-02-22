package no.skatteetaten.aurora.boober.service

import org.springframework.beans.factory.annotation.Autowired

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import io.fabric8.openshift.api.model.ImageStream
import io.fabric8.openshift.api.model.ImageStreamStatus
import io.fabric8.openshift.api.model.NamedTagEventList
import io.fabric8.openshift.api.model.TagEvent
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import no.skatteetaten.aurora.boober.service.vault.VaultService
import no.skatteetaten.aurora.boober.utils.ImageStreamUtilsKt

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

  def "Should perform release and not generate a deployRequest given new image"() {
    given:
      def updatedImageStream = new ImageStream(
          status: new ImageStreamStatus(tags: [new NamedTagEventList(items: [new TagEvent(image: '123')])]))
      openShiftClient.getImageStream('aos-booberdev', 'reference') >> ImageStreamUtilsKt.toJsonNode(updatedImageStream)


    when:
      List<AuroraDeployResult> deployResults = deployService.
          executeDeploy(affiliation, [new ApplicationId("imagestreamtest", "reference")], [], true)

    then:
      def result = deployResults[0]
      result.openShiftResponses.size() == 8
      result.openShiftResponses[7].responseBody.at("/kind").asText() == "ImageStreamTag"
  }

  def "Should perform release and generate a imageStreamTag and deployRequest given the same image"() {
    given:
      openShiftClient.getImageStream('aos-booberdev', 'reference') >> ImageStreamUtilsKt.toJsonNode(new ImageStream())

    when:
      List<AuroraDeployResult> deployResults = deployService.
          executeDeploy(affiliation, [new ApplicationId(ENV_NAME, "reference")], [], true)

    then:
      def result = deployResults[0]
      result.openShiftResponses.size() == 9
      result.openShiftResponses[7].responseBody.at("/kind").asText() == "ImageStreamTag"
      result.openShiftResponses[8].responseBody.at("/kind").asText() == "DeploymentRequest"
  }

  def "Should perform release of paused env and not generate a redeploy request"() {
    when:
      List<AuroraDeployResult> deployResults = deployService.
          executeDeploy(affiliation, [new ApplicationId(ENV_NAME, APP_NAME)], [], true)

    then:
      def result = deployResults[0]
      result.auroraDeploymentSpec.deploy.flags.pause
      result.openShiftResponses.size() == 9

  }


/*
TODO: fix tests

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

  @Ignore("This test needs to be fixed. Does no complete.")
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
*/

}
