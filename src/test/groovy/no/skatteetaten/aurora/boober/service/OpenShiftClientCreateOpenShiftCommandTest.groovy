package no.skatteetaten.aurora.boober.service

import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess

import static no.skatteetaten.aurora.boober.model.ApplicationId.aid

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer

import com.fasterxml.jackson.databind.JsonNode

import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand

class OpenShiftClientCreateOpenShiftCommandTest extends AbstractAuroraDeploymentSpecSpringTest {

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

  def "Updates the generated DeploymentConfig with relevant existing data"() {

    given:
      osClusterMock.expect(requestTo("$openShiftUrl/oapi/v1/namespaces/aos/deploymentconfigs/webleveranse")).
          andRespond(withSuccess(loadResource("webleveranse.json"), MediaType.APPLICATION_JSON))

      AuroraDeploymentSpec deploymentSpec = createDeploymentSpec(auroraConfigJson, aid("utv", "webleveranse"))
      JsonNode deploymentConfig = objectGenerator.generateDeploymentConfig(deploymentSpec, "deploy-id")

    when:
      OpenshiftCommand command = client.createOpenShiftCommand("aos", deploymentConfig, true)

    then: "Preserves the lastTriggeredImage"
      def lastTriggeredImagePath = "/spec/triggers/0/imageChangeParams/lastTriggeredImage"
      command.generated.at(lastTriggeredImagePath).missingNode
      command.previous.at(lastTriggeredImagePath).textValue() != null
      command.payload.at(lastTriggeredImagePath).textValue() == command.previous.at(lastTriggeredImagePath).textValue()

    and: "Preserves the container image attributes"
      (0..1).forEach {
        def imagePath = "/spec/template/spec/containers/$it/image"
        assert command.generated.at(imagePath).missingNode
        assert command.previous.at(imagePath).textValue() != null
        assert command.payload.at(imagePath).textValue() == command.previous.at(imagePath).textValue()
      }
  }
}
