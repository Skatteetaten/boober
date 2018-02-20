package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode

import io.fabric8.kubernetes.api.model.ObjectReference
import io.fabric8.openshift.api.model.DeploymentConfig
import io.fabric8.openshift.api.model.DeploymentConfigSpec
import io.fabric8.openshift.api.model.DeploymentTriggerImageChangeParams
import io.fabric8.openshift.api.model.DeploymentTriggerPolicy
import io.fabric8.openshift.api.model.ImageStream
import io.fabric8.openshift.api.model.ImageStreamSpec
import io.fabric8.openshift.api.model.ImageStreamStatus
import io.fabric8.openshift.api.model.NamedTagEventList
import io.fabric8.openshift.api.model.TagEvent
import io.fabric8.openshift.api.model.TagReference
import no.skatteetaten.aurora.boober.model.AuroraDeployEnvironment
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.Permission
import no.skatteetaten.aurora.boober.model.Permissions
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import spock.lang.Specification

class RedeployServiceTest extends Specification {
  def jsonNode = Mock(JsonNode) {
    get("kind") >> Mock(JsonNode) {
      asText() >> "imagestream"
    }
    toString() >> "{}"
  }
  def openShiftResponse = new OpenShiftResponse(new OpenshiftCommand(OperationType.CREATE, Mock(JsonNode)), jsonNode)
  def deployDeploymentSpec = createDeploymentSpec(TemplateType.deploy)
  def developmentDeploymentSpec = createDeploymentSpec(TemplateType.development)

  def openShiftClient = Mock(OpenShiftClient)
  def openShiftObjectGenerator = Mock(OpenShiftObjectGenerator)

  def imageStream = createImageStream()
  def deploymentConfig = createDeploymentConfig()

  def redeployService = new RedeployService(openShiftClient, openShiftObjectGenerator)

  void setup() {
    openShiftObjectGenerator.generateImageStream('affiliation', deployDeploymentSpec) >> jsonNode
    openShiftClient.performOpenShiftCommand('affiliation', null) >> openShiftResponse
  }

  def "Trigger redeploy given deployment request return success"() {
    when:
      def response = redeployService.triggerRedeploy(deployDeploymentSpec, null, deploymentConfig)

    then:
      response.success
      response.openShiftResponses.size() == 1
  }

  def "Trigger redeploy given image already imported return success"() {
    when:
      def response = redeployService.triggerRedeploy(deployDeploymentSpec, imageStream, deploymentConfig)

    then:
      response.success
      response.openShiftResponses.size() == 2
  }

  def "Trigger redeploy given development template type return success"() {
    when:
      def response = redeployService.triggerRedeploy(developmentDeploymentSpec, imageStream, deploymentConfig)

    then:
      response.success
  }

  private static ImageStream createImageStream() {
    return new ImageStream(
        status: new ImageStreamStatus(tags: [new NamedTagEventList(items: [new TagEvent(image: '123')])]),
        spec: new ImageStreamSpec(tags: [new TagReference(name: 'tag-name', from: new ObjectReference(name: 'imagestream-name'))]))
  }

  private static DeploymentConfig createDeploymentConfig() {
    def imageChangeParams = new DeploymentTriggerImageChangeParams(
        from: new ObjectReference(name: 'deploymentconfig-name:version'))
    return new DeploymentConfig(
        spec: new DeploymentConfigSpec(triggers: [new DeploymentTriggerPolicy(imageChangeParams: imageChangeParams)]))
  }

  private static AuroraDeploymentSpec createDeploymentSpec(TemplateType type) {
    new AuroraDeploymentSpec('', type, '', [:], '',
        new AuroraDeployEnvironment('affiliation', '', new Permissions(new Permission(null, null), null)),
        null, null, null, null, null, null)
  }
}
