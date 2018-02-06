package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.model.TemplateType.build
import static no.skatteetaten.aurora.boober.model.TemplateType.deploy
import static no.skatteetaten.aurora.boober.model.TemplateType.development
import static no.skatteetaten.aurora.boober.service.openshift.OperationType.CREATE

import org.springframework.beans.factory.annotation.Autowired

import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfigHelperKt
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand

@DefaultOverride(auroraConfig = false)
class RedeployServiceGenerateRedeployResourceTest extends AbstractMockedOpenShiftSpecification {

  @Autowired
  OpenShiftClient openShiftClient

  @Autowired
  RedeployService redeployService

  @Autowired
  ObjectMapper mapper

  public static final String ENV_NAME = "booberdev"
  public static final String APP_NAME = "aos-simple"
  final ApplicationId aid = new ApplicationId(ENV_NAME, APP_NAME)

  def setup() {
    openShiftClient.createOpenShiftCommand(_, _, _) >> { new OpenshiftCommand(CREATE, it[1]) }
    openShiftClient.performOpenShiftCommand(_, _) >> {
      OpenshiftCommand cmd = it[1]
      new OpenShiftResponse(cmd, cmd.payload)
    }
  }

  def resultFiles = AuroraConfigHelperKt.getResultFiles(new ApplicationId("booberdev", "reference"))

  def imageStream = resultFiles["imagestream/reference"]
  def dc = resultFiles["deploymentconfig/reference"]

  def mockedCommand = new OpenshiftCommand(CREATE, dc, null, null)
  def dcResponse = new OpenShiftResponse(mockedCommand, dc)
  def isResponse = new OpenShiftResponse(mockedCommand, imageStream)
  def redeployContext = new RedeployContext([])

  def docker = "docker/foo/bar:baz"

  def "Should not create any resource on build flow"() {
    given:
      def templateType = build
      def name = "boober"
    when:
      def result = redeployService.generateRedeployResource(templateType, name, redeployContext)

    then:
      result == null
  }

  def "Should not create redeploy resource when development flow and we create bc"() {
    given:
      def templateType = development
      def name = "boober"

    when:
      def result = redeployService.generateRedeployResource(templateType, name, redeployContext)

    then:
      result == null
  }

  def "Should create DeploymentRequest when no ImageStream is present and DeploymentConfig has changed and template is deploy"() {
    given:
      def templateType = deploy
      def name = "boober"
      def context = new RedeployContext([dcResponse])
    when:
      def result = redeployService.generateRedeployResource(templateType, name, context)

    then:
      result.get("kind").asText() == "DeploymentRequest"
  }

  def "Should not create redeploy resource if there is no ImageStream and DeploymentConfig"() {
    expect:
      def templateType = deploy
      def name = "boober"
      redeployService.generateRedeployResource(templateType, name, redeployContext) == null
  }

  def "Should create imagestream import resource when objects are created and template is deploy"() {
    given:
      def templateType = deploy
      def name = "boober"
      def context = new RedeployContext([dcResponse, isResponse])

    when:
      def result = redeployService.generateRedeployResource(templateType, name, context)

    then:
      result.get("kind").asText() == "ImageStreamImport"
  }
}
