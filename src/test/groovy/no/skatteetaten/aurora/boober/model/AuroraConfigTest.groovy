package no.skatteetaten.aurora.boober.model

import no.skatteetaten.aurora.boober.service.ApplicationId
import spock.lang.Specification

class AuroraConfigTest extends Specification {

  def "Returns files for application"() {
    given:
      def files = createMockFiles("about.json", "referanse.json", "utv/about.json", "utv/referanse.json")
      def auroraConfig = new AuroraConfig(files, [:])

    when:
      def filesForApplication = auroraConfig.getFilesForApplication(new ApplicationId("utv", "referanse"))

    then:
      filesForApplication.size() == 4
  }

  def "Fails when some files for application are missing"() {
    given:
      def files = createMockFiles("about.json", "referanse.json", "utv/about.json")
      def auroraConfig = new AuroraConfig(files, [:])

    when:
      auroraConfig.getFilesForApplication(new ApplicationId("utv", "referanse"))

    then: "Should be missing utv/referanse.json"
      def ex = thrown(IllegalArgumentException)
      ex.message.contains("utv/referanse.json")
  }

  Map<String, Map<String, Object>> createMockFiles(String... files) {
    files.collectEntries { [(it): [:]] }
  }
}
