package no.skatteetaten.aurora.boober.service

import java.nio.charset.Charset

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import spock.lang.Ignore

@Ignore
//TODO: We mock log service so does this add value at all?
class DeployServiceFromGitFailTest extends AbstractMockedOpenShiftSpecification {

  @Autowired
  OpenShiftClient openShiftClient

  @Autowired
  DeployService deployService

  @Autowired
  GitService gitService

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
      def body = '''{ "response": "failed"}'''.bytes
      def cause = new HttpClientErrorException(HttpStatus.BAD_REQUEST, "Bad Request", body, Charset.defaultCharset())
      def error = new OpenShiftException("Error saving url", cause)
      new OpenShiftResponse(cmd, cmd.payload, false, error.message)
    }
    openShiftClient.createOpenShiftDeleteCommands(_, _, _, _) >> []

  }

  def "Should perform release that fails and mark it as failed"() {
    when:
      deployService.executeDeploy(affiliation, [new ApplicationId(ENV_NAME, APP_NAME)])

    then:
      def git = gitService.checkoutRepository(affiliation)

      def history = gitService.getTagHistory(git)
      history.size() == 1
      def revTag = history[0]

      revTag.taggerIdent != null
      revTag.fullMessage.startsWith("""{"deployId":""")
      revTag.tagName.startsWith("FAILED/utv.aos-booberdev.aos-simple/")
      git.close()

  }

}
