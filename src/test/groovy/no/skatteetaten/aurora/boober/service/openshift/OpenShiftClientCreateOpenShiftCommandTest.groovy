package no.skatteetaten.aurora.boober.service.openshift

import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess

import static no.skatteetaten.aurora.boober.model.ApplicationId.aid

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer

import com.fasterxml.jackson.databind.JsonNode

import no.skatteetaten.aurora.boober.model.AuroraDeployEnvironment
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.AbstractAuroraDeploymentSpecSpringTest
import no.skatteetaten.aurora.boober.service.OpenShiftObjectGenerator

class OpenShiftClientCreateOpenShiftCommandTest extends AbstractAuroraDeploymentSpecSpringTest {

  String ENVIRONMENT = "utv"

  String NAMESPACE = "$AFFILIATION-$ENVIRONMENT"

  @Value('${openshift.url}')
  String openShiftUrl

  @Autowired
  MockRestServiceServer osClusterMock

  def auroraConfigJson = [
      "about.json"           : DEFAULT_ABOUT,
      "utv/about.json"       : DEFAULT_UTV_ABOUT,
      "webleveranse.json"    : WEB_LEVERANSE,
      "utv/webleveranse.json": '''{
  "type" : "development",
  "version" : "dev-SNAPSHOT"
}'''
  ]

  @Autowired
  OpenShiftObjectGenerator objectGenerator

  @Autowired
  OpenShiftClient client

  def "Gets existing resource from OpenShift and merges"() {

    given:
      osClusterMock.expect(requestTo("$openShiftUrl/oapi/v1/namespaces/$NAMESPACE/deploymentconfigs/webleveranse")).
          andRespond(withSuccess(loadResource("dc-webleveranse.json"), MediaType.APPLICATION_JSON))

      AuroraDeploymentSpec deploymentSpec = createDeploymentSpec(auroraConfigJson, aid(ENVIRONMENT, "webleveranse"))
      JsonNode deploymentConfig = objectGenerator.generateDeploymentConfig("deploy-id", deploymentSpec, null)

    when:
      OpenshiftCommand command = client.createOpenShiftCommand(NAMESPACE, deploymentConfig, true, false)

    then:
      def resourceVersion = "/metadata/resourceVersion"
      command.payload.at(resourceVersion).textValue() == command.previous.at(resourceVersion).textValue()
  }
}
