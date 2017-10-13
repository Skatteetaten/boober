package no.skatteetaten.aurora.boober.service

import org.springframework.beans.factory.annotation.Autowired

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.service.internal.ApplicationConfigException
import no.skatteetaten.aurora.boober.service.internal.AuroraConfigException
import no.skatteetaten.aurora.boober.service.internal.AuroraVersioningException

class DeployBundleServiceTest extends AbstractMockedOpenShiftSpecification {

  public static final String ENV_NAME = "secrettest"
  public static final String APP_NAME = "aos-simple"
  def aid = new ApplicationId(ENV_NAME, APP_NAME)
  def affiliation = "aos"

  @Autowired
  GitService gitService

  private AuroraConfig getAuroraConfigFromGit(String affiliation, boolean decryptSecrets) {

    def git = gitService.checkoutRepoForAffiliation(affiliation)
    def files = gitService.getAllFilesInRepo(git)
    def auroraConfig = deployBundleService.createAuroraConfigFromFiles(files, "aos")
    gitService.closeRepository(git)

    return auroraConfig
  }

  def "Should not update one file in AuroraConfig if version is wrong"() {
    given:
      def fileToChange = "secrettest/aos-simple.json"

      def newFile = mapper.convertValue([], JsonNode.class)

    when:

      deployBundleService.updateAuroraConfigFile("aos", fileToChange, newFile, "wrong version", true)
    then:

      def e = thrown(AuroraVersioningException)
      e.errors.size() == 1

  }

  def "Should update one file in AuroraConfig"() {
    given:
      def storedConfig = deployBundleService.findAuroraConfig("aos")

      def fileToChange = "secrettest/aos-simple.json"
      def theFileToChange = storedConfig.auroraConfigFiles.find { it.name == fileToChange }

      def newFile = mapper.convertValue([], JsonNode.class)

    when:
      deployBundleService.updateAuroraConfigFile("aos", fileToChange, newFile, theFileToChange.version, true)


    then:
      def git = gitService.checkoutRepoForAffiliation("aos")
      def gitLog = git.log().call().head()
      gitService.closeRepository(git)
      gitLog.authorIdent.name == "Test User"
      gitLog.fullMessage == "Added: 0, Modified: 1, Deleted: 0"
  }

  def "Should successfully save AuroraConfig"() {
    given:

      def json = mapper.convertValue(["name": "test%qwe)"], JsonNode.class)
      def newFile = new AuroraConfigFile("foo", json, false, null)
      def newConfig = new AuroraConfig([newFile], affiliation)

    when:
      deployBundleService.saveAuroraConfig(newConfig, false)

    then:
      def git = gitService.checkoutRepoForAffiliation("aos")
      def gitLog = git.log().call().head()
      gitService.closeRepository(git)

      gitLog.fullMessage.contains("Added: 1")
  }

  def "Should get error trying to set version as int"() {
    given:
      def gitAuroraConfig = getAuroraConfigFromGit("aos", false)

      def jsonOp = """[{
  "op": "replace",
  "path": "/version",
  "value": 3
}]
"""

    when:
      def filename = "${aid.environment}/${aid.application}.json"

      def version = gitAuroraConfig.auroraConfigFiles.find { it.name == filename }.version
      deployBundleService.patchAuroraConfigFile("aos", filename, jsonOp, version, false)

    then:
      def ex = thrown(AuroraConfigException)
      ex.errors[0].messages[0].message == "Version must be set as string"
  }

  def "Should patch AuroraConfigFile and push changes to git"() {
    given:
      def gitAuroraConfig = getAuroraConfigFromGit("aos", false)

      def jsonOp = """[{
  "op": "replace",
  "path": "/version",
  "value": "3"
}]
"""

    when:
      def filename = "${aid.environment}/${aid.application}.json"

      def version = gitAuroraConfig.auroraConfigFiles.find { it.name == filename }.version
      def patchedAuroraConfig = deployBundleService.patchAuroraConfigFile("aos", filename, jsonOp, version, false)
      def git = gitService.checkoutRepoForAffiliation("aos")
      def gitLog = git.log().call().head()
      gitService.closeRepository(git)

    then:
      gitLog.fullMessage == "Added: 0, Modified: 1, Deleted: 0"
      def patchedFile = patchedAuroraConfig.auroraConfigFiles.find { it.name == filename }
      patchedFile.contents.at("/version").textValue() == "3"
  }


  def "Should return error when name is too long"() {

    given:
      def overrideFile = mapper.
          convertValue(["name": "this-is-a-really-really-very-very-long-name-that-is-not-alloweed-over-40-char"],
              JsonNode.class)
      def overrides = [new AuroraConfigFile("${aid.environment}/${aid.application}.json", overrideFile, true, null)]
    when:
      deployBundleService.createAuroraDeploymentSpec("aos", aid, overrides)

    then:
      def ex = thrown(ApplicationConfigException)
      ex.errors[0].field.path == '/name'
  }

  def "Should return error when name is not valid DNS952 label"() {

    given:
      def overrideFile = mapper.convertValue(["name": "test%qwe)"], JsonNode.class)
      def overrides = [new AuroraConfigFile("${aid.environment}/${aid.application}.json", overrideFile, true, null)]
    when:
      deployBundleService.createAuroraDeploymentSpec("aos", aid, overrides)

    then:
      def ex = thrown(ApplicationConfigException)
      ex.errors[0].field.path == '/name'
  }

  def "Should return error when there are unmapped paths"() {

    given:
      def overrideFile = mapper.convertValue(["foo": "test%qwe)"], JsonNode.class)
      def overrides = [new AuroraConfigFile("${aid.environment}/${aid.application}.json", overrideFile, true, null)]
    when:
      deployBundleService.createAuroraDeploymentSpec("aos", aid, overrides)

    then:
      def e = thrown(ApplicationConfigException)
      def error = e.errors[0]
      error.fileName == "${aid.environment}/${aid.application}.json.override"
      error.message == "/foo is not a valid config field pointer"
  }

  @DefaultOverride(auroraConfig = false)
  def "Should throw ValidationException due to missing required properties"() {

    given:
      aid = new ApplicationId("booberdev", "aos-simple")
      Map<String, JsonNode> files = AuroraConfigHelperKt.getSampleFiles(aid)
      (files.get("aos-simple.json") as ObjectNode).remove("version")
      (files.get("$aid.environment/aos-simple.json" as String) as ObjectNode).remove("version")
      AuroraConfig auroraConfig =
          new AuroraConfig(files.collect { new AuroraConfigFile(it.key, it.value, false, null) }, "aos")
      GitServiceHelperKt.createInitRepo(auroraConfig.affiliation)

    when:
      deployBundleService.saveAuroraConfig(auroraConfig, false)

    then:
      def ex = thrown(AuroraConfigException)
      ex.errors[0].messages[0].message == "Version must be set as string"
  }
}
