package no.skatteetaten.aurora.boober.model

import kotlin.Pair
import spock.lang.Specification

class AuroraConfigTest extends Specification {

  def a() {
    given:
      def files = createMockFiles("about.json", "referanse.json", "utv/about.json", "utv/referanse.json")
      def auroraConfig = new AuroraConfig("referanse", "utv", files)

    when:
      def result = auroraConfig.applicationsToDeploy()

    then:
      result.size() == 1
      result.get(0) == new Pair("utv", "referanse")
  }

  def b() {
    given:
      def files = createMockFiles("about.json", "referanse.json", "utv/about.json")
      def auroraConfig = new AuroraConfig("referanse", "utv", files)

    when:
      def result = auroraConfig.applicationsToDeploy()
      def pair = result.get(0)
      auroraConfig.getFilesForApplication(pair.first, pair.second)

    then: "Should be missing utv/referanse.json"
      def ex = thrown(IllegalArgumentException)
      ex.message.contains("utv/referanse.json")
  }

  Map<String, Map<String, Object>> createMockFiles(String... files) {
    files.collectEntries { [(it): [:]] }
  }
}
