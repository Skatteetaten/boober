package no.skatteetaten.aurora.boober.service.internal

import com.fasterxml.jackson.databind.ObjectMapper

import spock.lang.Specification

class ImageStreamImportGeneratorTest extends Specification {

  def "Create ImageStreamImport with imageStreamName and dockerImageUrl return no null values"() {
    given:
      def generator = new ImageStreamImportGenerator()

    when:
      def imageStreamImport = generator.create('dockerImageUrl', 'imageStreamName', "imageStreamNamespace")
      def json = new ObjectMapper().writeValueAsString(imageStreamImport)

    then:
      imageStreamImport != null
      !json.contains("null")
  }

}
