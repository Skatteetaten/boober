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

class DeployServiceProjectTerminatingTest extends AbstractMockedOpenShiftSpecification {

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
    openShiftClient.projectExistsAndIsActive(_) >> {
      throw new IllegalStateException("Project is terminating")
    }
  }

  def "Should return with error if project is terminating"() {
    when:
      deployService.executeDeploy(affiliation, [new ApplicationId(ENV_NAME, APP_NAME)])

    then:
      thrown(IllegalStateException)

  }

}
