package no.skatteetaten.aurora.boober.model

import no.skatteetaten.aurora.boober.service.ApplicationId
import spock.lang.Specification

class AuroraConfigTest extends Specification {

  def "Should fetch secrets"() {
    given:
      def auroraConfig = new AuroraConfig([:], ["/tmp/foo/bar/secret1.properties": "Secret stuff"], [:])

    when:
      def secretsForFolder = auroraConfig.getSecrets("/tmp/foo/bar")

    then:
      secretsForFolder.size() == 1
      secretsForFolder.get("secret1.properties") != null
  }

  def "Should fetch secrets with trailing slash"() {
    given:
      def auroraConfig = new AuroraConfig([:], ["/tmp/foo/bar/secret1.properties": "Secret stuff"], [:])

    when:
      def secretsForFolder = auroraConfig.getSecrets("/tmp/foo/bar/")

    then:
      secretsForFolder.size() == 1
      secretsForFolder.get("secret1.properties") != null
  }

  def "Returns files for application"() {
    given:
      def files = createMockFiles("about.json", "referanse.json", "utv/about.json", "utv/referanse.json")
      def auroraConfig = new AuroraConfig(files, [:], [:])

    when:
      def filesForApplication = auroraConfig.getFilesForApplication(new ApplicationId("utv", "referanse"))

    then:
      filesForApplication.size() == 4
  }

  def "Returns files for application with about override"() {
    given:
      def files = createMockFiles("about.json", "referanse.json", "utv/about.json", "utv/referanse.json")
      def auroraConfig = new AuroraConfig(files, [:], ["about.json": [:]])

    when:
      def filesForApplication = auroraConfig.
          getFilesForApplication(new ApplicationId("utv", "referanse"))

    then:
      filesForApplication.size() == 5
  }

  def "Returns files for application with app override"() {
    given:
      def files = createMockFiles("about.json", "referanse.json", "utv/about.json", "utv/referanse.json")
      def auroraConfig = new AuroraConfig(files, [:], ["referanse.json": [:]])

    when:
      def filesForApplication = auroraConfig.
          getFilesForApplication(new ApplicationId("utv", "referanse"))

    then:
      filesForApplication.size() == 5
  }

  def "Returns files for application with app for env override"() {
    given:
      def files = createMockFiles("about.json", "referanse.json", "utv/about.json", "utv/referanse.json")
      def auroraConfig = new AuroraConfig(files, [:], ["utv/referanse.json": [:]])

    when:
      def filesForApplication = auroraConfig.
          getFilesForApplication(new ApplicationId("utv", "referanse"))

    then:
      filesForApplication.size() == 5
  }

  def "Returns files for application with app for not valid override"() {
    given:
      def files = createMockFiles("about.json", "referanse.json", "utv/about.json", "utv/referanse.json")
      def auroraConfig = new AuroraConfig(files, [:], ["utv/referanse2.json": [:]])

    when:
      def filesForApplication = auroraConfig.
          getFilesForApplication(new ApplicationId("utv", "referanse"))

    then:
      filesForApplication.size() == 4
  }

  def "Fails when some files for application are missing"() {
    given:
      def files = createMockFiles("about.json", "referanse.json", "utv/about.json")
      def auroraConfig = new AuroraConfig(files, [:], [:])

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
