package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode

import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.utils.ImageInformation
import spock.lang.Specification

class RedeployServiceGenerateRedeployResourceTest extends Specification {

  def openShiftObjectGenerator = Mock(OpenShiftObjectGenerator)
  def redeployService = new RedeployService(Mock(OpenShiftClient), openShiftObjectGenerator)

  def "Should not create any resource given on null values for imageInformation or imageName"() {
    given:
      def redeployContext = new RedeployContext(null, null)

    when:
      def result = redeployService.generateImageStreamImportResource(redeployContext)

    then:
      result == null
  }

  def "Should create ImageStreamImport when imageInformation and imageName is set"() {
    given:
      def redeployContext = Mock(RedeployContext) {
        findImageInformation() >> new ImageInformation('', 'image-stream-name', '')
        findImageName() >> 'image-name'
      }

    when:
      def result = redeployService.generateImageStreamImportResource(redeployContext)

    then:
      1 * openShiftObjectGenerator.generateImageStreamImport('image-stream-name', 'image-name') >> Mock(JsonNode)
      result != null
  }
}
