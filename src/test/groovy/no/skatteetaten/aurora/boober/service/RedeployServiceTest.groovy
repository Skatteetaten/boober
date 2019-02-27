package no.skatteetaten.aurora.boober.service

import static com.fasterxml.jackson.databind.node.NullNode.getInstance

import static no.skatteetaten.aurora.boober.utils.OpenShiftTestDataBuilders.deploymentConfig
import static no.skatteetaten.aurora.boober.utils.OpenShiftTestDataBuilders.imageStream
import static no.skatteetaten.aurora.boober.utils.OpenShiftTestDataBuilders.imageStreamImportResponse

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import spock.lang.Specification

class RedeployServiceTest extends Specification {
  def defaultImageHash = '123'
  def emptyJsonNode = getInstance()

  def deploymentRequest = [
      "kind"      : "DeploymentRequest",
      "apiVersion": "apps.openshift.io/v1",
      "name"      : "foobar",
      "latest"    : true,
      "force"     : true
  ]

  def deploymentRequestNode = new ObjectMapper().convertValue(deploymentRequest, JsonNode.class)
  def imageStream = imageStream()
  def deploymentConfig = deploymentConfig()

  def openShiftObjectGenerator = Mock(OpenShiftObjectGenerator) {
    generateDeploymentRequest('foobar') >> deploymentRequestNode
  }
  def openShiftClient = Mock(OpenShiftClient)
  def redeployService = new RedeployService(openShiftClient, openShiftObjectGenerator)

  def "Should not run explicit deploy for development type"() {
    when:
      def response = redeployService.triggerRedeploy([deploymentConfig], TemplateType.development)

    then:
      response.success
      response.message == "No deploy made since type=development, deploy via oc start-build."
  }

  def "Should throw error if dc is null"() {
    given:
      openShiftClient.performOpenShiftCommand('aos-test', _ as OpenshiftCommand) >> openShiftResponse()

    when:
      redeployService.triggerRedeploy([], TemplateType.deploy)

    then:
      def e = thrown(IllegalArgumentException)
      e.message == 'Missing DeploymentConfig'
  }

  def "Redeploy given null ImageStream return success"() {

    given:
      openShiftClient.performOpenShiftCommand('aos-test', _ as OpenshiftCommand) >> openShiftResponse()

    when:
      def response = redeployService.triggerRedeploy([deploymentConfig], TemplateType.deploy)

    then:
      response.success
      response.openShiftResponses.size() == 1
  }

  def "Redeploy with paused deploy will run manual deploy"() {
    given:
      openShiftClient.performOpenShiftCommand('aos-test', _ as OpenshiftCommand) >> imageStreamImportResponse()

    when:
      def response = redeployService.
          triggerRedeploy([deploymentConfig("ImageChange", 0), imageStream], TemplateType.deploy)

    then:
      response.success
      response.message == "No new application version found. Config changes deployment succeeded."
      response.openShiftResponses.size() == 1
  }

  def "Redeploy with newly created imagestream will not import"() {
    given:
      openShiftClient.performOpenShiftCommand('aos-test', _ as OpenshiftCommand) >> imageStreamImportResponse()

    when:
      def response = redeployService.
          triggerRedeploy([deploymentConfig, imageStream("dockerUrl", OperationType.CREATE)], TemplateType.deploy)

    then:
      response.success
      response.openShiftResponses.size() == 0
      response.message == "New application version found."
  }

  def "Redeploy given image is already imported return success"() {
    given:
      openShiftClient.performOpenShiftCommand('aos-test', _ as OpenshiftCommand) >> imageStreamImportResponse()

    when:
      def response = redeployService.triggerRedeploy([deploymentConfig, imageStream], TemplateType.deploy)

    then:
      response.success
      response.openShiftResponses.size() == 1
  }

  def "Redeploy given image is not imported return success"() {
    given:
      openShiftClient.performOpenShiftCommand('aos-test', _ as OpenshiftCommand) >>
          imageStreamImportResponse('234')

    when:
      def response = redeployService.triggerRedeploy([deploymentConfig, imageStream], TemplateType.deploy)

    then:
      response.success
      response.openShiftResponses.size() == 1
  }

  def "Redeploy given no type in DeploymentConfig perform deployment request and return success"() {
    given:
      def deploymentConfigWithoutType = deploymentConfig(null)
      openShiftClient.performOpenShiftCommand('aos-test', _ as OpenshiftCommand) >> openShiftResponse()

    when:
      def response = redeployService.triggerRedeploy([deploymentConfigWithoutType, imageStream], TemplateType.deploy)

    then:
      response.success
      response.openShiftResponses.size() == 1
  }

  def "Redeploy with same image will run explicit deploy"() {
    given:
      openShiftClient.performOpenShiftCommand('aos-test', _ as OpenshiftCommand) >> openShiftResponse()

    when:
      def response = redeployService.
          triggerRedeploy(
              [deploymentConfig, imageStream, imageStreamImportResponse(defaultImageHash)],
              TemplateType.deploy)

    then:
      response.success
      response.openShiftResponses.size() == 1
      response.message == "No new application version found. Config changes deployment succeeded."
  }

  def "Redeploy with different image will not run explicit deploy"() {
    when:
      def response = redeployService.
          triggerRedeploy([deploymentConfig, imageStream, imageStreamImportResponse("hash")],
              TemplateType.deploy)

    then:
      response.success
      response.openShiftResponses.size() == 0
      response.message == "New application version found."
  }

  private OpenShiftResponse openShiftResponse(JsonNode responseBody = emptyJsonNode) {
    return new OpenShiftResponse(new OpenshiftCommand(OperationType.CREATE, "", emptyJsonNode), responseBody)
  }

}
