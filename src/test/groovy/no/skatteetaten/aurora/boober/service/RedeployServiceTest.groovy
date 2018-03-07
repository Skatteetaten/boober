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
import io.fabric8.openshift.api.model.ImageStreamTag
import io.fabric8.openshift.api.model.NamedTagEventList
import io.fabric8.openshift.api.model.TagEvent
import io.fabric8.openshift.api.model.TagEventCondition
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import no.skatteetaten.aurora.boober.utils.ImageStreamTagUtilsKt
import no.skatteetaten.aurora.boober.utils.ImageStreamUtilsKt
import spock.lang.Specification

class RedeployServiceTest extends Specification {
  def defaultImageHash = '123'
  def emptyJsonNode = NullNode.getInstance()

  def deploymentConfig = createDeploymentConfig()

  def imageStream = createImageStream()
  def imageStreamResponse = imageStreamResponse()

  def openShiftClient = Mock(OpenShiftClient)
  def openShiftObjectGenerator = Mock(OpenShiftObjectGenerator) {
    generateDeploymentRequest('name') >> emptyJsonNode
  }
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
      openShiftClient.performOpenShiftCommand('affiliation', _ as OpenshiftCommand) >> imageStreamResponse
      openShiftClient.getUpdatedImageStream('affiliation', 'name', '123') >> imageStreamResponse

    when:
      def response = redeployService.triggerRedeploy(deploymentConfig, imageStream)

    then:
      response.success
      response.openShiftResponses.size() == 3
  }

  def "Rollout deployment given image is not imported return success"() {
    given:
      openShiftClient.performOpenShiftCommand('affiliation', _ as OpenshiftCommand) >> imageStreamResponse
      openShiftClient.getUpdatedImageStream('affiliation', 'name', '123') >> imageStreamResponse('234')

    when:
      def response = redeployService.triggerRedeploy(deploymentConfig, imageStream)

    then:
      response.success
      response.openShiftResponses.size() == 2
  }

  def "Rollout deployment given failure from ImageStreamTag command return failed"() {
    given:
      def errorMessage = 'failed ImageStreamTag'
      openShiftClient.performOpenShiftCommand('affiliation', _ as OpenshiftCommand) >> failedResponse(errorMessage)

    when:
      def response = redeployService.triggerRedeploy(deploymentConfig, imageStream)

    then:
      !response.success
      response.message == errorMessage
      response.openShiftResponses.size() == 1
  }

  def "Rollout deployment given null response body in ImageStream response return failed"() {
    given:
      openShiftClient.performOpenShiftCommand('affiliation', _ as OpenshiftCommand) >> imageStreamResponse
      openShiftClient.getUpdatedImageStream('affiliation', 'name', '123') >> nullBodyResponse()

    when:
      def response = redeployService.triggerRedeploy(deploymentConfig, imageStream)

    then:
      !response.success
      response.message == 'Missing information in deployment spec'
      response.openShiftResponses.size() == 2
  }

  def "Rollout deployment given null response body in ImageStreamTag response return failed"() {
    given:
      openShiftClient.performOpenShiftCommand('affiliation', _ as OpenshiftCommand) >> nullBodyResponse()

    when:
      redeployService.triggerRedeploy(deploymentConfig, imageStream)

    then:
      def e = thrown(IllegalArgumentException)
      e.message == 'Missing ImageStreamTag response body'
  }

  def "Rollout deployment given failure from ImageStream response return failed"() {
    given:
      def errorMessage = 'ImageStream error message'
      openShiftClient.performOpenShiftCommand('affiliation', _ as OpenshiftCommand) >> imageStreamResponse
      openShiftClient.getUpdatedImageStream('affiliation', 'name', '123') >> failedResponse(errorMessage)

    when:
      def response = redeployService.triggerRedeploy(deploymentConfig, imageStream)

    then:
      !response.success
      response.message == errorMessage
      response.openShiftResponses.size() == 2
  }

  def "Rollout deployment given error message in ImageStream response return failed"() {
    given:
      def errorMessage = 'ImageStream error message'
      openShiftClient.performOpenShiftCommand('affiliation', _ as OpenshiftCommand) >> imageStreamResponse
      openShiftClient.getUpdatedImageStream('affiliation', 'name', '123') >> failedImageStreamResponse(errorMessage)

    when:
      def response = redeployService.triggerRedeploy(deploymentConfig, imageStream)

    then:
      !response.success
      response.message == errorMessage
      response.openShiftResponses.size() == 2
  }

  def "Rollout deployment given error message in ImageStreamTag response return failed"() {
    given:
      def errorMessage = 'ImageStreamTag error message'
      openShiftClient.performOpenShiftCommand('affiliation', _ as OpenshiftCommand) >> failedImageStreamTagResponse(errorMessage)

    when:
      def response = redeployService.triggerRedeploy(deploymentConfig, imageStream)

    then:
      !response.success
      response.message == errorMessage
      response.openShiftResponses.size() == 1
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

  private ImageStream createImageStream(String imageHash = defaultImageHash, boolean status = true,
      String errorMessage = '') {
    return new ImageStream(
        metadata: new ObjectMeta(name: 'name', resourceVersion: '123'),
        status: new ImageStreamStatus(
            tags: [new NamedTagEventList(
                tag: 'default',
                conditions: [new TagEventCondition(status: Boolean.toString(status), message: errorMessage)],
                items: [new TagEvent(image: imageHash)])]
        ))
  }

  private OpenShiftResponse imageStreamResponse(String imageHash = defaultImageHash) {
    def jsonNode = ImageStreamUtilsKt.toJsonNode(createImageStream(imageHash))
    return new OpenShiftResponse(
        new OpenshiftCommand(OperationType.CREATE, emptyJsonNode), jsonNode
    )
  }

  private OpenShiftResponse openShiftResponse(JsonNode responseBody = emptyJsonNode) {
    return new OpenShiftResponse(new OpenshiftCommand(OperationType.CREATE, emptyJsonNode), responseBody)
  }

  private OpenShiftResponse nullBodyResponse() {
    return new OpenShiftResponse(
        new OpenshiftCommand(OperationType.CREATE, emptyJsonNode), null, true, ''
    )
  }

  private OpenShiftResponse failedResponse(JsonNode jsonNode = emptyJsonNode, String errorMessage) {
    return new OpenShiftResponse(
        new OpenshiftCommand(OperationType.CREATE, emptyJsonNode), jsonNode, false, errorMessage
    )
  }

  private OpenShiftResponse failedImageStreamResponse(String errorMessage) {
    def imageStream = createImageStream(defaultImageHash, false, errorMessage)
    return openShiftResponse(ImageStreamUtilsKt.toJsonNode(imageStream))
  }

  private OpenShiftResponse failedImageStreamTagResponse(String errorMessage) {
    def imageStreamTag = new ImageStreamTag(
        conditions: [new TagEventCondition(status: 'failure', message: errorMessage)]
    )
    return openShiftResponse(ImageStreamTagUtilsKt.toJsonNode(imageStreamTag))
  }
}
