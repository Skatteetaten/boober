package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.service.ApplicationId
import spock.lang.Specification

class AuroraConfigTest extends Specification {

  def mapper = new ObjectMapper()

  def "url parse test"() {
    given:
      def stringUrl = "http://www.vg.no/"

      URL url = new URL(stringUrl)

    when:

      def key = stringUrl
      key = key.replace("https://", "")
      key = key.replace("http://", "")
      if (key.startsWith("/")) {
        key = key.substring(1)
      }
      if (key.endsWith("/")) {
        key = key.substring(0, key.length() - 1)
      }
      key = key.replaceAll(":", "_")
      key = key.replaceAll("/", "_")
      key = key.replaceAll("-", "_")

      url.toExternalForm()
      def portSegment = "_" + url.port
      if (url.port == -1) {
        portSegment = ""
      }

      def pathSegment = url.path
      if (pathSegment.equalsIgnoreCase("/")) {
        pathSegment = ""
      }

      def normalizedUrl = (url.host + portSegment + pathSegment).replaceAll(":|-|/", "_")

    then:
      normalizedUrl == "www.vg.no"
      // key == "www.vg.no_index.html"
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
