package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.databind.ObjectMapper

class AuroraConfigTest extends AbstractAuroraConfigTest {

  def mapper = new ObjectMapper()

  def aid = new ApplicationDeploymentRef("booberdev", "console")

  def "Should get all application ids for AuroraConfig"() {
    given:
      def auroraConfig = AuroraConfigHelperKt.createAuroraConfig(aid)
    when:
      def applicationIds = auroraConfig.getApplicationIds()

    then:
      def console = applicationIds.get(0)
      console.application == "console"
      console.environment == "booberdev"
  }

  def "Should update file"() {
    given:
      def auroraConfig = AuroraConfigHelperKt.createAuroraConfig(aid)
      def updates = '''{ "version": "4"}'''

    when:

      def updateFileResponse= auroraConfig.updateFile("booberdev/console.json", updates)
      def updatedAuroraConfig=updateFileResponse.second
    then:
      def version = updatedAuroraConfig.getFiles().stream()
          .filter({ it.configName == "booberdev/console.json" })
          .map({ it.asJsonNode.get("version").asText() })
          .findFirst()

      version.isPresent()
      "4" == version.get()
  }

  def "Should create file when updating nonexisting file"() {
    given:
      def auroraConfig = AuroraConfigHelperKt.createAuroraConfig(aid)

      def updates = '''{ "version": "4"}'''
      def fileName = "boobertest/console.json"

    expect:
      !auroraConfig.findFile(fileName)

    when:
      AuroraConfig updatedAuroraConfig = auroraConfig.updateFile(fileName, updates).second

    then:
      def version = updatedAuroraConfig.getFiles().stream()
          .filter({ it.configName == fileName })
          .map({ it.asJsonNode.get("version").asText() })
          .findFirst()

      version.isPresent()
      "4" == version.get()
  }

  def "Returns files for application"() {
    given:
      def auroraConfig = AuroraConfigHelperKt.createAuroraConfig(aid)

    when:
      def filesForApplication = auroraConfig.getFilesForApplication(aid)

    then:
      filesForApplication.size() == 4
  }

  def "Returns files for application with about override"() {
    given:
      def auroraConfig = AuroraConfigHelperKt.createAuroraConfig(aid)

    when:
      def filesForApplication = auroraConfig.getFilesForApplication(aid, [overrideFile("about.json")])

    then:
      filesForApplication.size() == 5
  }

  def "Returns files for application with app override"() {
    given:
      def auroraConfig = AuroraConfigHelperKt.createAuroraConfig(aid)

    when:
      def filesForApplication = auroraConfig.getFilesForApplication(aid, [overrideFile("console.json")])

    then:
      filesForApplication.size() == 5
  }

  def "Returns files for application with app for env override"() {
    given:
      def auroraConfig = AuroraConfigHelperKt.createAuroraConfig(aid)

    when:
      def filesForApplication = auroraConfig.
          getFilesForApplication(aid, [overrideFile("${aid.environment}/${aid.application}.json")])

    then:
      filesForApplication.size() == 5
  }

  def "Fails when some files for application are missing"() {
    given:
      def referanseAid = new ApplicationDeploymentRef("utv", "referanse")
      def files = createMockFiles("about.json", "referanse.json", "utv/about.json")
      def auroraConfig = new AuroraConfig(files, "aos", "master")

    when:
      auroraConfig.getFilesForApplication(referanseAid)

      then: "Should be missing utv/referanse"
      def ex = thrown(IllegalArgumentException)
      ex.message.contains("utv/referanse")
  }

  def "Includes base file in files for application when set"() {
    given:
      def aid = new ApplicationDeploymentRef("booberdev", "aos-complex")
      def auroraConfig = AuroraConfigHelperKt.getAuroraConfigSamples()

    when:
      def filesForApplication = auroraConfig.getFilesForApplication(aid)

    then:
      filesForApplication.size() == 4
      def applicationFile = filesForApplication.find { it.name == 'booberdev/aos-complex.json' }
      String baseFile = applicationFile.asJsonNode.get("baseFile").textValue()
      filesForApplication.any { it.name == baseFile }
  }

  def "Patch file"() {
    given:
      def aid = DEFAULT_AID
      def filename = "${aid.environment}/${aid.application}.json"
      def auroraConfig = createAuroraConfig(modify(defaultAuroraConfig(), filename, { version = "1.0.0" }))

      def jsonOp = """[{
  "op": "replace",
  "path": "/version",
  "value": "3"
}]
"""
    when:
      def version = auroraConfig.getFiles().find { it.name == filename }.version
      def patchFileResponse = auroraConfig.patchFile(filename, jsonOp, version)
      def patchedAuroraConfig=patchFileResponse.second

    then:
      def patchedFile = patchedAuroraConfig.getFiles().find { it.name == filename }
      patchedFile.asJsonNode.at("/version").textValue() == "3"
  }

  List<AuroraConfigFile> createMockFiles(String... files) {
    files.collect { new AuroraConfigFile(it, "{}", false) }
  }

  def overrideFile(String fileName) {
    new AuroraConfigFile(fileName, "{}", true)
  }
}
