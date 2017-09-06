package no.skatteetaten.aurora.boober.service

import java.nio.charset.Charset

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException

import no.skatteetaten.aurora.boober.controller.internal.DeployParams
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.service.internal.OpenShiftException
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType

class DeployServiceFromGitFailTest extends AbstractMockedOpenShiftSpecification {

  @Autowired
  OpenShiftClient openShiftClient

  @Autowired
  DeployService deployService

  @Autowired
  GitService gitService

  public static final String ENV_NAME = "booberdev"
  public static final String APP_NAME = "aos-simple"
  def affiliation = "aos"

  final ApplicationId aid = new ApplicationId(ENV_NAME, APP_NAME)

  def setup() {
    openShiftClient.prepare(_, _) >> { new OpenshiftCommand(OperationType.CREATE, it[1]) }
    openShiftClient.performOpenShiftCommand(_, _) >> {
      def cmd = it[0]
      def body = '''{ "response": "failed"}'''.bytes
      def cause = new HttpClientErrorException(HttpStatus.BAD_REQUEST, "Bad Request", body, Charset.defaultCharset())
      def error = new OpenShiftException("Error saving url", cause)
      new OpenShiftResponse(cmd, cmd.payload, false, error.message)
    }
    openShiftClient.createOpenshiftDeleteCommands(_, _, _, _) >> []

  }

  def "Should perform release that fails and mark it as failed"() {
    when:
      deployService.executeDeploy(affiliation, new DeployParams([ENV_NAME], [APP_NAME], [], true))

    then:
      def git = gitService.checkoutRepoForAffiliation(affiliation)

      def history = gitService.tagHistory(git)
      history.size() == 1
      def revTag = history[0]

      revTag.taggerIdent != null
      revTag.fullMessage.startsWith("""{"deployId":""")
      revTag.tagName.startsWith("FAILED/aos-booberdev.aos-simple/")
      gitService.closeRepository(git)

  }

}
