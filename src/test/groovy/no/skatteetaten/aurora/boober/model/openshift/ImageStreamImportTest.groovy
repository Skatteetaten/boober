package no.skatteetaten.aurora.boober.model.openshift

import spock.lang.Specification

class ImageStreamImportTest extends Specification {

  def "isSameImage given null return false"() {
    given:
      def imageStreamImport = new ImageStreamImport()

    when:
      def sameImage = imageStreamImport.isSameImage(null)

    then:
      !sameImage
  }
}
