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
  def openShiftResponse = new OpenShiftResponse(new OpenshiftCommand(OperationType.CREATE, Mock(JsonNode)))
  def deploymentSpec = new AuroraDeploymentSpec('', TemplateType.deploy, '', [:], '',
      new AuroraDeployEnvironment('', '', new Permissions(new Permission(null, null), null)), null, null, null,
      null, null, null)

  def verificationSuccess = new RedeployService.VerificationResult()
  def verificationFailed = new RedeployService.VerificationResult(false, '')

  def openshiftClient = Mock(OpenShiftClient)
  def openshiftObjectGenerator = Mock(OpenShiftObjectGenerator)
  def redeployContext = Mock(RedeployContext)

  def redeployService

  void setup() {
    redeployService = new RedeployService(openshiftClient, openshiftObjectGenerator)
    redeployContext.findImageInformation() >> new RedeployService.ImageInformation('', '', '')
    redeployContext.findImageName() >> ''

    openshiftObjectGenerator.generateImageStreamImport('', '') >> Mock(JsonNode)
    openshiftClient.performOpenShiftCommand('', null) >> openShiftResponse
  }

  def "Trigger redeploy given no image stream import required return success"() {
    given:
      redeployContext.verifyResponse(openShiftResponse) >> verificationSuccess
      redeployContext.noImageStreamImportRequired(openShiftResponse) >> false

    when:
      def response = redeployService.triggerRedeploy(deploymentSpec, redeployContext)

    then:
      response.success
      response.openShiftResponses.size() == 2
  }

  def "Trigger redeploy given image stream import required return success"() {
    given:
      redeployContext.verifyResponse(openShiftResponse) >> verificationSuccess
      redeployContext.noImageStreamImportRequired(openShiftResponse) >> true

    when:
      def response = redeployService.triggerRedeploy(deploymentSpec, redeployContext)

    then:
      response.success
      response.openShiftResponses.size() == 1
  }

  def "Trigger redeploy given invalid image stream import response return failed"() {
    given:
      redeployContext.verifyResponse(openShiftResponse) >> verificationFailed

    when:
      def response = redeployService.triggerRedeploy(deploymentSpec, redeployContext)

    then:
      !response.success
      response.openShiftResponses.size() == 1
  }
}
