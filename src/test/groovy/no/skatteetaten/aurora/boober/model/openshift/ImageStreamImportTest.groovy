package no.skatteetaten.aurora.boober.model.openshift

import spock.lang.Specification

class ImageStreamImportTest extends Specification {

  def "isDifferentImage given null return true"() {
    given:
      def imageStreamImport = new ImageStreamImport()

    when:
      def differentImage = imageStreamImport.isDifferentImage(null)

    then:
      differentImage
  }
}
