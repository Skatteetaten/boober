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
  def emptyOpenShiftResponse = new OpenShiftResponse(new OpenshiftCommand(OperationType.CREATE, Mock(JsonNode)))

  def redeployService
  def openshiftClient = Mock(OpenShiftClient)
  def openshiftObjectGenerator = Mock(OpenShiftObjectGenerator)
  def redeployContext = Mock(RedeployContext)

  void setup() {
    redeployService = new RedeployService(openshiftClient, openshiftObjectGenerator)

    redeployContext.getImageStream() >> emptyOpenShiftResponse
    redeployContext.getDeploymentConfig() >> emptyOpenShiftResponse
    redeployContext.findImageInformation() >> new RedeployService.ImageInformation('', '', '')
    redeployContext.findImageName() >> ''
    redeployContext.verifyResponse(emptyOpenShiftResponse) >> new RedeployService.VerificationResult()
    redeployContext.noImageStreamImportRequired(null) >> false

    openshiftObjectGenerator.generateImageStreamImport('', '') >> Mock(JsonNode)
    openshiftClient.performOpenShiftCommand('', null) >> emptyOpenShiftResponse
  }

  def "Trigger redeploy"() {
    given:
      def deploymentSpec = createAuroraDeploymentSpec()

    when:
      def response = redeployService.triggerRedeploy(deploymentSpec, redeployContext)

    then:
      response.success
  }

  private static AuroraDeploymentSpec createAuroraDeploymentSpec() {
    new AuroraDeploymentSpec('', TemplateType.deploy, '', [:], '',
        new AuroraDeployEnvironment('', '', new Permissions(new Permission(null, null), null)), null, null, null,
        null,
        null, null)
  }
}
