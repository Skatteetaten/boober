package no.skatteetaten.aurora.boober.service

import org.springframework.beans.factory.annotation.Autowired

import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient

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
  def ref = new AuroraConfigRef("aos", "master")

  final ApplicationId aid = new ApplicationId(ENV_NAME, APP_NAME)

  def setup() {
    openShiftClient.projectExists(_) >> {
      throw new IllegalStateException("Project is terminating")
    }
  }

  def "Should return with error if project is terminating"() {
    when:
      deployService.executeDeploy(ref, [new ApplicationId(ENV_NAME, APP_NAME)])

    then:
      thrown(IllegalStateException)
  }
}
