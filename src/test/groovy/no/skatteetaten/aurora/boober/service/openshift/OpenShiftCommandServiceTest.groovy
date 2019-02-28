package no.skatteetaten.aurora.boober.service.openshift

import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess

import static no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef.aid

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer

import com.fasterxml.jackson.databind.JsonNode

import io.fabric8.kubernetes.api.model.OwnerReference
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpecInternal
import no.skatteetaten.aurora.boober.service.AbstractAuroraDeploymentSpecSpringTest
import no.skatteetaten.aurora.boober.service.OpenShiftCommandService
import no.skatteetaten.aurora.boober.service.OpenShiftObjectGenerator

class OpenShiftCommandServiceTest extends AbstractAuroraDeploymentSpecSpringTest {

  String ENVIRONMENT = "utv"

  String NAMESPACE = "$AFFILIATION-$ENVIRONMENT"

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

  @Value('${openshift.url}')
  String openShiftUrl

  @Autowired
  OpenShiftObjectGenerator objectGenerator

  @Autowired
  OpenShiftClient client

  OpenShiftCommandService commandBuilder

  def setup() {
    commandBuilder = new OpenShiftCommandService(client, objectGenerator)
  }

  def "Gets existing resource from OpenShift and merges"() {

    given:
      osClusterMock.
          expect(requestTo(
              "$openShiftUrl/apis/apps.openshift.io/v1/namespaces/$NAMESPACE/deploymentconfigs/webleveranse")).
          andRespond(withSuccess(loadResource("dc-webleveranse.json"), MediaType.APPLICATION_JSON))

      AuroraDeploymentSpecInternal deploymentSpec =
          createDeploymentSpec(auroraConfigJson, aid(ENVIRONMENT, "webleveranse"))
      JsonNode deploymentConfig = objectGenerator.
          generateDeploymentConfig("deploy-id", deploymentSpec, null, new OwnerReference())

    when:
      OpenshiftCommand command = commandBuilder.createOpenShiftCommand(NAMESPACE, deploymentConfig, true, false)

    then:
      def resourceVersion = "/metadata/resourceVersion"
      command.payload.at(resourceVersion).textValue() == command.previous.at(resourceVersion).textValue()
  }

}
