package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import io.fabric8.kubernetes.api.model.ObjectReference
import io.fabric8.openshift.api.model.ImageStream
import io.fabric8.openshift.api.model.ImageStreamSpec
import io.fabric8.openshift.api.model.ImageStreamStatus
import io.fabric8.openshift.api.model.ImageStreamTag
import io.fabric8.openshift.api.model.NamedTagEventList
import io.fabric8.openshift.api.model.TagEvent
import io.fabric8.openshift.api.model.TagEventCondition
import io.fabric8.openshift.api.model.TagReference
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import no.skatteetaten.aurora.boober.utils.ImageStreamTagUtilsKt
import no.skatteetaten.aurora.boober.utils.ImageStreamUtilsKt
import spock.lang.Specification

class RedeployServiceTest extends Specification {
  def defaultImageHash = '123'
  def emptyJsonNode = new ObjectMapper().readTree('{}')

  def imageStream = createImageStream()
  def imageStreamResponse = imageStreamResponse()

  def openShiftClient = Mock(OpenShiftClient)
  def redeployService = new RedeployService(openShiftClient, Mock(OpenShiftCommandBuilder),
      Mock(OpenShiftObjectGenerator))

  def "Request deployment given null ImageStream return success"() {
    given:
      openShiftClient.performOpenShiftCommand('affiliation', null) >> openShiftResponse()

    when:
      def response = redeployService.triggerRedeploy('affiliation', 'name', null)

    then:
      response.success
      response.openShiftResponses.size() == 1
  }

  def "Rollout deployment given image is already imported return success"() {
    given:
      openShiftClient.performOpenShiftCommand('affiliation', null) >> imageStreamResponse
      openShiftClient.getImageStream('affiliation', 'name') >> imageStreamResponse

    when:
      def response = redeployService.triggerRedeploy('affiliation', 'name', imageStream)

    then:
      response.success
      response.openShiftResponses.size() == 3
  }

  def "Rollout deployment given image is not imported return success"() {
    given:
      openShiftClient.performOpenShiftCommand('affiliation', null) >> imageStreamResponse
      openShiftClient.getImageStream('affiliation', 'name') >> imageStreamResponse('234')

    when:
      def response = redeployService.triggerRedeploy('affiliation', 'name', imageStream)

    then:
      response.success
      response.openShiftResponses.size() == 2
  }

  def "Rollout deployment given failure from ImageStreamTag command return failed"() {
    given:
      def errorMessage = 'failed ImageStreamTag'
      openShiftClient.performOpenShiftCommand('affiliation', null) >> failedResponse(errorMessage)

    when:
      def response = redeployService.triggerRedeploy('affiliation', 'name', imageStream)

    then:
      !response.success
      response.message == errorMessage
      response.openShiftResponses.size() == 1
  }

  def "Rollout deployment given null response body in ImageStream response return failed"() {
    given:
      openShiftClient.performOpenShiftCommand('affiliation', null) >> imageStreamResponse
      openShiftClient.getImageStream('affiliation', 'name') >> nullBodyResponse()

    when:
      def response = redeployService.triggerRedeploy('affiliation', 'name', imageStream)

    then:
      !response.success
      response.message == 'Missing information in deployment spec'
      response.openShiftResponses.size() == 2
  }

  def "Rollout deployment given failure from ImageStream response return failed"() {
    given:
      def errorMessage = 'ImageStream error message'
      openShiftClient.performOpenShiftCommand('affiliation', null) >> imageStreamResponse
      openShiftClient.getImageStream('affiliation', 'name') >> failedResponse(errorMessage)

    when:
      def response = redeployService.triggerRedeploy('affiliation', 'name', imageStream)

    then:
      !response.success
      response.message == errorMessage
      response.openShiftResponses.size() == 2
  }

  def "Rollout deployment given error message in ImageStream response return failed"() {
    given:
      def errorMessage = 'ImageStream error message'
      openShiftClient.performOpenShiftCommand('affiliation', null) >> imageStreamResponse
      openShiftClient.getImageStream('affiliation', 'name') >> failedImageStreamResponse(errorMessage)

    when:
      def response = redeployService.triggerRedeploy('affiliation', 'name', imageStream)

    then:
      !response.success
      response.message == errorMessage
      response.openShiftResponses.size() == 2
  }

  def "Rollout deployment given error message in ImageStreamTag response return failed"() {
    given:
      def errorMessage = 'ImageStreamTag error message'
      openShiftClient.performOpenShiftCommand('affiliation', null) >> failedImageStreamTagResponse(errorMessage)

    when:
      def response = redeployService.triggerRedeploy('affiliation', 'name', imageStream)

    then:
      !response.success
      response.message == errorMessage
      response.openShiftResponses.size() == 1
  }

  private ImageStream createImageStream(String imageHash = defaultImageHash, boolean status = true,
      String errorMessage = '') {
    return new ImageStream(
        status: new ImageStreamStatus(
            tags: [new NamedTagEventList(
                conditions: [new TagEventCondition(status: Boolean.toString(status), message: errorMessage)],
                items: [new TagEvent(image: imageHash)])]
        ),
        spec: new ImageStreamSpec(
            tags: [new TagReference(name: 'tag-name', from: new ObjectReference(name: 'imagestream-name'))]))
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
