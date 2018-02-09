package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode

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
  def jsonNode = Mock(JsonNode)
  def openShiftResponse = new OpenShiftResponse(new OpenshiftCommand(OperationType.CREATE, jsonNode))
  def deployDeploymentSpec = createDeploymentSpec(TemplateType.deploy)
  def developmentDeploymentSpec = createDeploymentSpec(TemplateType.development)

  def verificationSuccess = new RedeployContext.VerificationResult()
  def verificationFailed = new RedeployContext.VerificationResult(false, 'verification failed')

  def openShiftClient = Mock(OpenShiftClient)
  def openShiftObjectGenerator = Mock(OpenShiftObjectGenerator)
  def redeployContext = Mock(RedeployContext)

  def redeployService = new RedeployService(openShiftClient, openShiftObjectGenerator)

  void setup() {
    redeployContext.findImageInformation() >> new RedeployContext.ImageInformation('', 'image-stream-name', '')
    redeployContext.findImageName() >> 'docker-image'

    openShiftObjectGenerator.generateImageStreamImport('image-stream-name', 'docker-image') >> jsonNode
    openShiftClient.performOpenShiftCommand('affiliation', null) >> openShiftResponse
  }

  def "Trigger redeploy given deployment request return success"() {
    given:
      redeployContext.verifyResponse(openShiftResponse) >> verificationSuccess
      redeployContext.isDeploymentRequest() >> true

    when:
      def response = redeployService.triggerRedeploy(deployDeploymentSpec, redeployContext)

    then:
      response.success
      response.openShiftResponses.size() == 1
  }

  def "Trigger redeploy given no image stream import required return success"() {
    given:
      redeployContext.verifyResponse(openShiftResponse) >> verificationSuccess

    when:
      def response = redeployService.triggerRedeploy(deployDeploymentSpec, redeployContext)

    then:
      response.success
      response.openShiftResponses.size() == 2
  }

  def "Trigger redeploy given image stream import required return success"() {
    given:
      redeployContext.verifyResponse(openShiftResponse) >> verificationSuccess
      redeployContext.didImportImage(openShiftResponse) >> true

    when:
      def response = redeployService.triggerRedeploy(deployDeploymentSpec, redeployContext)

    then:
      response.success
      response.openShiftResponses.size() == 1
  }

  def "Trigger redeploy given invalid image stream import response return failed"() {
    given:
      redeployContext.verifyResponse(openShiftResponse) >> verificationFailed

    when:
      def response = redeployService.triggerRedeploy(deployDeploymentSpec, redeployContext)

    then:
      !response.success
      response.openShiftResponses.size() == 1
      response.message == 'verification failed'
  }

  def "Trigger redeploy given development template type return success"() {
    when:
      def response = redeployService.triggerRedeploy(developmentDeploymentSpec, redeployContext)

    then:
      response.success
  }

  private static AuroraDeploymentSpec createDeploymentSpec(TemplateType type) {
    new AuroraDeploymentSpec('', type, '', [:], '',
        new AuroraDeployEnvironment('affiliation', '', new Permissions(new Permission(null, null), null)),
        null, null, null, null, null, null)
  }
}
