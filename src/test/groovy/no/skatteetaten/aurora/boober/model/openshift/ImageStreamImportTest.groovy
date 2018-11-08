package no.skatteetaten.aurora.boober.model.openshift

import io.fabric8.openshift.api.model.ImageStreamImport
import spock.lang.Ignore
import spock.lang.Specification



class ImageStreamImportTest extends Specification {

  @Ignore("Extension methods not possible to use in groovy. rewrite in junit kotlin")
  def "isDifferentImage given null return true"() {
    given:
      def imageStreamImport = new ImageStreamImport()

    when:
      def differentImage = imageStreamImport.isDifferentImage(null)

    then:
      differentImage
  }
}
