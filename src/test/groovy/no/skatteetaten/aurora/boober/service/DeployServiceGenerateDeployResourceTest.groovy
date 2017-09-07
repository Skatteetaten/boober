package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.model.TemplateType.build
import static no.skatteetaten.aurora.boober.model.TemplateType.deploy
import static no.skatteetaten.aurora.boober.model.TemplateType.development
import static no.skatteetaten.aurora.boober.service.openshift.OperationType.CREATE
import static no.skatteetaten.aurora.boober.service.openshift.OperationType.UPDATE

import org.springframework.beans.factory.annotation.Autowired

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType

class DeployServiceGenerateDeployResourceTest extends AbstractMockedOpenShiftSpecification {

  @Autowired
  OpenShiftClient openShiftClient

  @Autowired
  DeployService deployService

  @Autowired
  ObjectMapper mapper

  public static final String ENV_NAME = "booberdev"
  public static final String APP_NAME = "aos-simple"
  final ApplicationId aid = new ApplicationId(ENV_NAME, APP_NAME)

  def setup() {
    openShiftClient.prepare(_, _) >> { new OpenshiftCommand(OperationType.CREATE, it[1]) }
    openShiftClient.performOpenShiftCommand(_, _) >> {
      OpenshiftCommand cmd = it[1]
      new OpenShiftResponse(cmd, cmd.payload)
    }
  }

  def docker = "docker/foo/bar:baz"

  def createOpenShiftResponse(String kind, OperationType operationType, int prevVersion, int currVersion) {
    def previous = mapper.
        convertValue(["kind": kind, "metadata": ["labels": ["releasedVersion": prevVersion]]], JsonNode.class)
    def payload = Mock(JsonNode)
    def response = mapper.
        convertValue(["kind": kind, "metadata": ["labels": ["releasedVersion": currVersion]]], JsonNode.class)

    return new OpenShiftResponse(new OpenshiftCommand(operationType, payload, previous, null), response)
  }

  def "Should  not create any resource on build flow"() {
    given:
      def templateType = build
      def name = "boober"
      def imagestream = createOpenShiftResponse("imagestream", UPDATE, 1, 1)

    when:
      def result = deployService.generateRedeployResource([imagestream], templateType, name, docker, true)

    then:
      result == null
  }

  def "Should not create redeploy resource when development flow and we create bc"() {
    given:
      def templateType = development
      def name = "boober"
      def bc = createOpenShiftResponse("buildconfig", CREATE, 1, 1)

    when:
      def result = deployService.generateRedeployResource([bc], templateType, name, docker, true)

    then:
      result == null
  }

  def "Should create DeploymentRequest when no ImageStream is present and DeploymentConfig has changed and template is deploy"() {
    given:
      def templateType = deploy
      def name = "boober"
      def deploymentConfig = createOpenShiftResponse("deploymentconfig", UPDATE, 1, 2)

    when:
      def result = deployService.generateRedeployResource([deploymentConfig], templateType, name, docker, true)

    then:
      result.get("kind").asText() == "DeploymentRequest"
  }

  def "Should not create redeploy resource if there is no ImageStream and DeploymentConfig"() {
    expect:
      def templateType = deploy
      def name = "boober"
      deployService.generateRedeployResource([], templateType, name, docker, true) == null
  }

  def "Should create DeploymentRequest when ImageStream has not changed but OperationType is Update and template is deploy"() {
    given:
      def templateType = deploy
      def name = "boober"
      def imagestream = createOpenShiftResponse("imagestream", UPDATE, 1, 1)
      imagestream.command

    when:
      def result = deployService.generateRedeployResource([imagestream], templateType, name, docker, true)

    then:
      result.get("kind").asText() == "DeploymentRequest"
  }

  def "Should create imagestream import resource when objects are created and template is deploy"() {
    given:
      def templateType = deploy
      def name = "boober"
      def imagestream = createOpenShiftResponse("imagestream", OperationType.CREATE, 1, 1)

    when:
      def result = deployService.generateRedeployResource([imagestream], templateType, name, docker, true)

    then:
      result.get("kind").asText() == "ImageStreamImport"
  }

  def "Should not create deploy request if deploy is false"() {
    given:
      def templateType = deploy
      def name = "boober"
      def imagestream = createOpenShiftResponse("imagestream", UPDATE, 1, 1)
      imagestream.command

    when:
      def result = deployService.generateRedeployResource([imagestream], templateType, name, docker, false)

    then:
      result == null
  }
}
