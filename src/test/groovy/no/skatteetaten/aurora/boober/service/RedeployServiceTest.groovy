package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode

import io.fabric8.kubernetes.api.model.ObjectMeta
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
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import no.skatteetaten.aurora.boober.utils.ImageStreamUtilsKt
import spock.lang.Specification

class RedeployServiceTest extends Specification {
  def imageStream = createImageStream('123')
  def deploymentConfig = createDeploymentConfig()

  def openShiftClient = Mock(OpenShiftClient)
  def redeployService = new RedeployService(openShiftClient, Mock(OpenShiftObjectGenerator))

  void setup() {
    openShiftClient.performOpenShiftCommand('affiliation', null) >>
        createOpenShiftResponse(imageStream)
  }

  def "Trigger redeploy given deployment request return success"() {
    when:
      def response = redeployService.triggerRedeploy(deploymentConfig, null)

    then:
      response.success
      response.openShiftResponses.size() == 1
  }

  def "Trigger redeploy given dc and is and image is already imported return success"() {
    when:
      def response = redeployService.triggerRedeploy(deploymentConfig, imageStream)

    then:
      1 * openShiftClient.getImageStream('affiliation', 'name') >> createOpenShiftResponse(imageStream)
      response.success
      response.openShiftResponses.size() == 3
  }

  def "Trigger redeploy given dc and is and image is not imported return success"() {
    when:
      def response = redeployService.triggerRedeploy(deploymentConfig, imageStream)

    then:
      1 * openShiftClient.getImageStream('affiliation', 'name') >> createOpenShiftResponse(createImageStream('234'))
      response.success
      response.openShiftResponses.size() == 2
  }

  private static ImageStream createImageStream(def imageHash) {
    return new ImageStream(
        status: new ImageStreamStatus(tags: [new NamedTagEventList(items: [new TagEvent(image: imageHash)])]),
        spec: new ImageStreamSpec(
            tags: [new TagReference(name: 'tag-name', from: new ObjectReference(name: 'imagestream-name'))]))
  }

  private static DeploymentConfig createDeploymentConfig() {
    def imageChangeParams = new DeploymentTriggerImageChangeParams(
        from: new ObjectReference(name: 'deploymentconfig-name:version'))
    return new DeploymentConfig(metadata: new ObjectMeta(namespace: 'affiliation', name: 'name'),
        spec: new DeploymentConfigSpec(triggers: [new DeploymentTriggerPolicy(imageChangeParams: imageChangeParams)]))
  }

  private OpenShiftResponse createOpenShiftResponse(def imageStream) {
    return new OpenShiftResponse(
        new OpenshiftCommand(OperationType.CREATE, Mock(JsonNode)), ImageStreamUtilsKt.toJsonNode(imageStream)
    )
  }
}
