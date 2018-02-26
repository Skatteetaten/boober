package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import io.fabric8.kubernetes.api.model.ObjectReference
import io.fabric8.openshift.api.model.ImageStream
import io.fabric8.openshift.api.model.ImageStreamSpec
import io.fabric8.openshift.api.model.ImageStreamStatus
import io.fabric8.openshift.api.model.NamedTagEventList
import io.fabric8.openshift.api.model.TagEvent
import io.fabric8.openshift.api.model.TagEventCondition
import io.fabric8.openshift.api.model.TagReference
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import no.skatteetaten.aurora.boober.utils.ImageStreamUtilsKt
import spock.lang.Specification

class RedeployServiceTest extends Specification {
  def defaultImageHash = '123'
  def emptyJsonNode = new ObjectMapper().readTree('{}')

  def imageStream = createImageStream()
  def imageStreamResponse = createImageStreamResponse()

  def openShiftClient = Mock(OpenShiftClient)
  def redeployService = new RedeployService(openShiftClient, Mock(OpenShiftCommandBuilder),
      Mock(OpenShiftObjectGenerator))

  def "Request deployment given null ImageStream return success"() {
    given:
      openShiftClient.performOpenShiftCommand('affiliation', null) >> createDeploymentRequestResponse()

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
      openShiftClient.getImageStream('affiliation', 'name') >> createImageStreamResponse('234')

    when:
      def response = redeployService.triggerRedeploy('affiliation', 'name', imageStream)

    then:
      response.success
      response.openShiftResponses.size() == 2
  }

  def "Rollout deployment given failure from ImageStreamTag command return failed"() {
    given:
      openShiftClient.performOpenShiftCommand('affiliation', null) >> createFailedImageStreamResponse()

    when:
      def response = redeployService.triggerRedeploy('affiliation', 'name', imageStream)

    then:
      !response.success
      response.openShiftResponses.size() == 1
  }

  def "Rollout deployment given failure when getting ImageStream response return failed"() {
    given:
      openShiftClient.performOpenShiftCommand('affiliation', null) >> imageStreamResponse
      openShiftClient.getImageStream('affiliation', 'name') >> createFailedImageStreamResponse(null)

    when:
      def response = redeployService.triggerRedeploy('affiliation', 'name', imageStream)

    then:
      !response.success
      response.openShiftResponses.size() == 2
  }

  def "Rollout deployment given error message in updated ImageStream return failed"() {
    given:
      openShiftClient.performOpenShiftCommand('affiliation', null) >> imageStreamResponse
      openShiftClient.getImageStream('affiliation', 'name') >> createImageStreamResponse('234', 'false')

    when:
      def response = redeployService.triggerRedeploy('affiliation', 'name', imageStream)

    then:
      !response.success
      response.openShiftResponses.size() == 2
  }

  private ImageStream createImageStream(String imageHash = defaultImageHash, String status = '') {
    return new ImageStream(
        status: new ImageStreamStatus(
            tags: [new NamedTagEventList(
                conditions: [new TagEventCondition(status: status, message: '')],
                items: [new TagEvent(image: imageHash)])]
        ),
        spec: new ImageStreamSpec(
            tags: [new TagReference(name: 'tag-name', from: new ObjectReference(name: 'imagestream-name'))]))
  }

  private OpenShiftResponse createImageStreamResponse(String imageHash = defaultImageHash,
      String status = '') {
    def jsonNode = ImageStreamUtilsKt.toJsonNode(createImageStream(imageHash, status))
    return new OpenShiftResponse(
        new OpenshiftCommand(OperationType.CREATE, emptyJsonNode), jsonNode
    )
  }

  private OpenShiftResponse createDeploymentRequestResponse() {
    return new OpenShiftResponse(new OpenshiftCommand(OperationType.CREATE, emptyJsonNode))
  }

  private OpenShiftResponse createFailedImageStreamResponse(JsonNode responseBody = emptyJsonNode) {
    return new OpenShiftResponse(
        new OpenshiftCommand(OperationType.CREATE, emptyJsonNode, ImageStreamUtilsKt.toJsonNode(imageStream)),
        responseBody,
        false
    )
  }
}
