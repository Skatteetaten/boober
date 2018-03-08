package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode

import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.api.model.ObjectReference
import io.fabric8.openshift.api.model.DeploymentConfig
import io.fabric8.openshift.api.model.DeploymentConfigSpec
import io.fabric8.openshift.api.model.DeploymentTriggerImageChangeParams
import io.fabric8.openshift.api.model.DeploymentTriggerPolicy
import io.fabric8.openshift.api.model.ImageStream
import io.fabric8.openshift.api.model.ImageStreamStatus
import io.fabric8.openshift.api.model.NamedTagEventList
import io.fabric8.openshift.api.model.TagEvent
import no.skatteetaten.aurora.boober.model.openshift.ConditionsItem
import no.skatteetaten.aurora.boober.model.openshift.ImageStreamImport
import no.skatteetaten.aurora.boober.model.openshift.Import
import no.skatteetaten.aurora.boober.model.openshift.ImportStatus
import no.skatteetaten.aurora.boober.model.openshift.ItemsItem
import no.skatteetaten.aurora.boober.model.openshift.Status
import no.skatteetaten.aurora.boober.model.openshift.TagsItem
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import spock.lang.Specification

class RedeployServiceTest extends Specification {
  def defaultImageHash = '123'
  def emptyJsonNode = NullNode.getInstance()

  def deploymentConfig = createDeploymentConfig()

  def imageStream = createImageStream()
  def imageStreamImportResponse = imageStreamImportResponse()

  def openShiftObjectGenerator = Mock(OpenShiftObjectGenerator) {
    generateDeploymentRequest('name') >> emptyJsonNode
  }
  def openShiftClient = Mock(OpenShiftClient)
  def redeployService = new RedeployService(openShiftClient, openShiftObjectGenerator)

  def "Request deployment given null ImageStream return success"() {
    given:
      openShiftClient.performOpenShiftCommand('affiliation', _ as OpenshiftCommand) >> openShiftResponse()

    when:
      def response = redeployService.triggerRedeploy(deploymentConfig, null)

    then:
      response.success
      response.openShiftResponses.size() == 1
  }

  def "Rollout deployment given image is already imported return success"() {
    given:
      openShiftClient.performOpenShiftCommand('affiliation', _ as OpenshiftCommand) >> imageStreamImportResponse

    when:
      def response = redeployService.triggerRedeploy(deploymentConfig, imageStream)

    then:
      response.success
      response.openShiftResponses.size() == 2
  }

  def "Rollout deployment given image is not imported return success"() {
    given:
      openShiftClient.performOpenShiftCommand('affiliation', _ as OpenshiftCommand) >> imageStreamImportResponse('234')

    when:
      def response = redeployService.triggerRedeploy(deploymentConfig, imageStream)

    then:
      response.success
      response.openShiftResponses.size() == 1
  }

  def "Rollout deployment given failure from ImageStreamImport command return failed"() {
    given:
      def errorMessage = 'failed ImageStreamImport'
      openShiftClient.performOpenShiftCommand('affiliation', _ as OpenshiftCommand) >> failedResponse(errorMessage)

    when:
      def response = redeployService.triggerRedeploy(deploymentConfig, imageStream)

    then:
      !response.success
      response.message == errorMessage
      response.openShiftResponses.size() == 1
  }

  def "Rollout deployment given error message in ImageStreamImport response return failed"() {
    given:
      def errorMessage = 'ImageStreamImport error message'
      openShiftClient.performOpenShiftCommand('affiliation', _ as OpenshiftCommand) >>
          failedImageStreamImportResponse(errorMessage)

    when:
      def response = redeployService.triggerRedeploy(deploymentConfig, imageStream)

    then:
      !response.success
      response.message == errorMessage
      response.openShiftResponses.size() == 1
  }

  def "Trigger redeploy given deploymentConfig without tag name throw IllegalArgumentException"() {
    given:
      def deploymentConfigWithoutTagName = new DeploymentConfig(
          metadata: new ObjectMeta(namespace: 'affiliation', name: 'name'))

    when:
      redeployService.triggerRedeploy(deploymentConfigWithoutTagName, imageStream)

    then:
      def e = thrown(IllegalArgumentException)
      e.message == 'No imageChangeTriggerName found'
  }

  private static DeploymentConfig createDeploymentConfig() {
    return new DeploymentConfig(
        metadata: new ObjectMeta(namespace: 'affiliation', name: 'name'),
        spec: new DeploymentConfigSpec(
            triggers: [new DeploymentTriggerPolicy(type: 'ImageChange',
                imageChangeParams: new DeploymentTriggerImageChangeParams(
                    from: new ObjectReference(name: 'referanse:default')))])
    )
  }

  private ImageStream createImageStream() {
    return new ImageStream(
        metadata: new ObjectMeta(name: 'name', resourceVersion: '123'),
        status: new ImageStreamStatus(
            tags: [new NamedTagEventList(
                items: [new TagEvent(image: defaultImageHash)])]
        ))
  }

  private ImageStreamImport createImageStreamImport(String imageHash = defaultImageHash, boolean status = true,
      String errorMessage = '') {
    return new ImageStreamImport(null, '', '', null,
        new Status([], new Import(null, null,
            new ImportStatus('', [new TagsItem('default',
                [new ItemsItem(0, imageHash, '', '')],
                [new ConditionsItem('ImportSuccess', Boolean.toString(status), '', '', errorMessage, 0)]
            )])
        ))
    )
  }

  private OpenShiftResponse imageStreamImportResponse(String imageHash = defaultImageHash) {
    def imageStreamImport = createImageStreamImport(imageHash)
    return new OpenShiftResponse(
        new OpenshiftCommand(OperationType.CREATE, emptyJsonNode), imageStreamImport.toJsonNode()
    )
  }

  private OpenShiftResponse openShiftResponse(JsonNode responseBody = emptyJsonNode) {
    return new OpenShiftResponse(new OpenshiftCommand(OperationType.CREATE, emptyJsonNode), responseBody)
  }

  private OpenShiftResponse failedResponse(JsonNode jsonNode = emptyJsonNode, String errorMessage) {
    return new OpenShiftResponse(
        new OpenshiftCommand(OperationType.CREATE, emptyJsonNode), jsonNode, false, errorMessage
    )
  }

  private OpenShiftResponse failedImageStreamImportResponse(String errorMessage) {
    def imageStreamImport = createImageStreamImport(defaultImageHash, false, errorMessage)
    return openShiftResponse(imageStreamImport.toJsonNode())
  }
}
