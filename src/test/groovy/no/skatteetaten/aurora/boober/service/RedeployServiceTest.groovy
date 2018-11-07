package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.NullNode

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
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.model.openshift.ConditionsItem
import no.skatteetaten.aurora.boober.model.openshift.ImageStreamImport
import no.skatteetaten.aurora.boober.model.openshift.Import
import no.skatteetaten.aurora.boober.model.openshift.ImportStatus
import no.skatteetaten.aurora.boober.model.openshift.ItemsItem
import no.skatteetaten.aurora.boober.model.openshift.Status
import no.skatteetaten.aurora.boober.model.openshift.TagsItem
import no.skatteetaten.aurora.boober.service.internal.ImageStreamImportGenerator
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import spock.lang.Ignore
import spock.lang.Specification

class RedeployServiceTest extends Specification {
  def defaultImageHash = '123'
  def emptyJsonNode = NullNode.getInstance()

  def imageStream = imageStream()
  def deploymentConfig = deploymentConfig()

  def openShiftObjectGenerator = Mock(OpenShiftObjectGenerator) {
    generateDeploymentRequest('name') >> emptyJsonNode
  }
  def openShiftClient = Mock(OpenShiftClient)
  def redeployService = new RedeployService(openShiftClient, openShiftObjectGenerator)

  def "Should not run explicit deploy for development type"() {
    when:
      def response = redeployService.triggerRedeploy([deploymentConfig], TemplateType.development)

    then:
      response.success
      response.message == "No explicit deploy was made with development type"
  }

  def "Should throw error if dc is null"() {
    given:
      openShiftClient.performOpenShiftCommand('affiliation', _ as OpenshiftCommand) >> openShiftResponse()

    when:
      redeployService.triggerRedeploy([], TemplateType.deploy)

    then:
      def e = thrown(IllegalArgumentException)
      e.message == 'Missing DeploymentConfig'
  }

  def "Redeploy given null ImageStream return success"() {

    given:
      openShiftClient.performOpenShiftCommand('affiliation', _ as OpenshiftCommand) >> openShiftResponse()

    when:
      def response = redeployService.triggerRedeploy([deploymentConfig], TemplateType.deploy)

    then:
      response.success
      response.openShiftResponses.size() == 1
  }

  def "Redeploy with paused deploy will not run manual deploy"() {
    given:
      openShiftClient.performOpenShiftCommand('affiliation', _ as OpenshiftCommand) >> imageStreamImportResponse()

    when:
      def response = redeployService.
          triggerRedeploy([deploymentConfig("ImageChange", 0), imageStream], TemplateType.deploy)

    then:
      response.success
      response.message == "Deploy was paused so no explicit deploy"
      response.openShiftResponses.size() == 0
  }

  def "Redeploy with newly created imagestream will not import"() {
    given:
      openShiftClient.performOpenShiftCommand('affiliation', _ as OpenshiftCommand) >> imageStreamImportResponse()

    when:
      def response = redeployService.
          triggerRedeploy([deploymentConfig, imageStream("dockerUrl", OperationType.CREATE)], TemplateType.deploy)

    then:
      response.success
      response.openShiftResponses.size() == 0
      response.message == "No explicit deploy was made for newly created imagestream"
  }

  def "Redeploy given image is already imported return success"() {
    given:
      openShiftClient.performOpenShiftCommand('affiliation', _ as OpenshiftCommand) >> imageStreamImportResponse()

    when:
      def response = redeployService.triggerRedeploy([deploymentConfig, imageStream], TemplateType.deploy)

    then:
      response.success
      response.openShiftResponses.size() == 1
  }

  def "Redeploy given image is not imported return success"() {
    given:
      openShiftClient.performOpenShiftCommand('affiliation', _ as OpenshiftCommand) >> imageStreamImportResponse('234')

    when:
      def response = redeployService.triggerRedeploy([deploymentConfig, imageStream], TemplateType.deploy)

    then:
      response.success
      response.openShiftResponses.size() == 1
  }


  def "Redeploy given no type in DeploymentConfig perform deployment request and return success"() {
    given:
      def deploymentConfigWithoutType = deploymentConfig(null)
      openShiftClient.performOpenShiftCommand('affiliation', _ as OpenshiftCommand) >> openShiftResponse()

    when:
      def response = redeployService.triggerRedeploy([deploymentConfigWithoutType, imageStream], TemplateType.deploy)

    then:
      response.success
      response.openShiftResponses.size() == 1
  }

  def "Redeploy with different image will not run explicit deploy"() {
    given:
      openShiftClient.performOpenShiftCommand('affiliation', _ as OpenshiftCommand) >> imageStreamImportResponse()

    when:
      def response = redeployService.
          triggerRedeploy([deploymentConfig, imageStream, imageStreamImportResponse("hash")], TemplateType.deploy)

    then:
      response.success
      response.openShiftResponses.size() == 0
      response.message == "Image is different so no explicit deploy"
  }

  private static OpenShiftResponse deploymentConfig(String type = 'ImageChange', int replicas = 1) {

    def deploymentConfig = new DeploymentConfig(
        metadata: new ObjectMeta(namespace: 'affiliation', name: 'name'),
        spec: new DeploymentConfigSpec(
            replicas: replicas,
            triggers: [new DeploymentTriggerPolicy(type: type,
                imageChangeParams: new DeploymentTriggerImageChangeParams(
                    from: new ObjectReference(name: 'referanse:default')))])
    )
    def mapper = new ObjectMapper();
    JsonNode dc = mapper.convertValue(deploymentConfig, JsonNode.class)
    def dcCommand = new OpenshiftCommand(OperationType.CREATE, dc, dc)
    return new OpenShiftResponse(dcCommand, dc, true)
  }

  private OpenShiftResponse imageStream(String dockerImageUrl = 'dockerImageUrl',
      OperationType type = OperationType.UPDATE) {
    def is = new ImageStream(
        metadata: new ObjectMeta(namespace: 'affiliation', name: 'name', resourceVersion: '123'),
        status: new ImageStreamStatus(
            tags: [new NamedTagEventList(items: [new TagEvent(image: defaultImageHash)])]
        ),
        spec: new ImageStreamSpec(
            tags: [new TagReference(name: 'default', from: new ObjectReference(name: dockerImageUrl))]
        ))
    def mapper = new ObjectMapper();
    JsonNode dc = mapper.convertValue(is, JsonNode.class)
    def dcCommand = new OpenshiftCommand(type, dc)
    return new OpenShiftResponse(dcCommand, dc, true)
  }

  private ImageStreamImport imageStreamImport(String imageHash = defaultImageHash, boolean status = true,
      String errorMessage = '') {
    return new ImageStreamImport(null, '', 'ImageStreamImport', null,
        new Status([], new Import(null, null,
            new ImportStatus('', [new TagsItem('default',
                [new ItemsItem(0, imageHash, '', '')],
                [new ConditionsItem('ImportSuccess', Boolean.toString(status), '', '', errorMessage, 0)]
            )])
        ))
    )
  }

  private OpenShiftResponse imageStreamImportResponse(String imageHash = defaultImageHash) {
    def imageStreamImport = imageStreamImport(imageHash)
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
    def imageStreamImport = imageStreamImport(defaultImageHash, false, errorMessage)
    return openShiftResponse(imageStreamImport.toJsonNode())
  }
}
