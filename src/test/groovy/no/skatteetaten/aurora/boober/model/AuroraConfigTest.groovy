package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.utils.SampleFilesCollector
import spock.lang.Specification

class AuroraConfigTest extends Specification {

  def mapper = new ObjectMapper()

  def "Should get all application ids for AuroraConfig"() {
    given:
      def files = SampleFilesCollector.getSampleFiles(new ApplicationId("booberdev", "console"))
      def auroraConfig =
          new AuroraConfig(files.collect { new AuroraConfigFile(it.key, it.value, false) }, [:])

    when:
      def applicationIds = auroraConfig.getApplicationIds("", "")

    then:
      def console = applicationIds.get(0)
      console.applicationName == "console"
      console.environmentName == "booberdev"
  }

  def "Should update file"() {

    given:
      def files = SampleFilesCollector.getSampleFiles(new ApplicationId("booberdev", "console"))
      def auroraConfig =
          new AuroraConfig(files.collect { new AuroraConfigFile(it.key, it.value, false) }, [:])

      def updates = mapper.convertValue(["version": "4"], JsonNode.class)

    when:
      def updatedAuroraConfig = auroraConfig.updateFile("booberdev/console.json", updates)

    then:
      def version = updatedAuroraConfig.getAuroraConfigFiles().stream()
          .filter({ it.configName == "booberdev/console.json" })
          .map({ it.contents.get("version").asText() })
          .findFirst()

      version.isPresent()
      "4" == version.get()
  }

  def "Should fetch secrets"() {
    given:
      def auroraConfig = new AuroraConfig([], ["/tmp/foo/bar/secret1.properties": "Secret stuff"])

    when:
      def secretsForFolder = auroraConfig.getSecrets("/tmp/foo/bar")

    then:
      secretsForFolder.size() == 1
      secretsForFolder.get("secret1.properties") != null
  }

  def "Should fetch secrets with trailing slash"() {
    given:
      def auroraConfig = new AuroraConfig([], ["/tmp/foo/bar/secret1.properties": "Secret stuff"])

    when:
      def secretsForFolder = auroraConfig.getSecrets("/tmp/foo/bar/")

    then:
      secretsForFolder.size() == 1
      secretsForFolder.get("secret1.properties") != null
  }

  def "Returns files for application"() {
    given:
      def files = createMockFiles("about.json", "referanse.json", "utv/about.json", "utv/referanse.json")
      def auroraConfig = new AuroraConfig(files, [:])

    when:
      def filesForApplication = auroraConfig.getFilesForApplication(new ApplicationId("utv", "referanse"), [])

    then:
      filesForApplication.size() == 4
  }

  def "Returns files for application with about override"() {
    given:
      def files = createMockFiles("about.json", "referanse.json", "utv/about.json", "utv/referanse.json")
      def auroraConfig = new AuroraConfig(files, [:])

    when:
      def filesForApplication = auroraConfig.
          getFilesForApplication(new ApplicationId("utv", "referanse"), [
              overrideFile("about.json")])

    then:
      filesForApplication.size() == 5
  }

  def "Returns files for application with app override"() {
    given:
      def files = createMockFiles("about.json", "referanse.json", "utv/about.json", "utv/referanse.json")
      def auroraConfig = new AuroraConfig(files, [:])

    when:
      def filesForApplication = auroraConfig.
          getFilesForApplication(new ApplicationId("utv", "referanse"),
              [overrideFile("referanse.json")])

    then:
      filesForApplication.size() == 5
  }

  def "Returns files for application with app for env override"() {
    given:
      def files = createMockFiles("about.json", "referanse.json", "utv/about.json", "utv/referanse.json")
      def auroraConfig = new AuroraConfig(files, [:])

    when:
      def filesForApplication = auroraConfig.
          getFilesForApplication(new ApplicationId("utv", "referanse"),
              [overrideFile("utv/referanse.json")])

    then:
      filesForApplication.size() == 5
  }

  def "Fails when some files for application are missing"() {
    given:
      def files = createMockFiles("about.json", "referanse.json", "utv/about.json")
      def auroraConfig = new AuroraConfig(files, [:])

    when:
      auroraConfig.getFilesForApplication(new ApplicationId("utv", "referanse"), [])

    then: "Should be missing utv/referanse.json"
      def ex = thrown(IllegalArgumentException)
      ex.message.contains("utv/referanse.json")
  }

  List<AuroraConfigFile> createMockFiles(String... files) {
    files.collect { new AuroraConfigFile(it, mapper.readValue("{}", JsonNode.class), false) }
  }

  def overrideFile(String fileName) {
    new AuroraConfigFile(fileName, mapper.readValue("{}", JsonNode.class), true)
  }
}
