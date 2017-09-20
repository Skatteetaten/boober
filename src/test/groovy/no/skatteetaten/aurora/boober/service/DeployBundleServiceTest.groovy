package no.skatteetaten.aurora.boober.service

import org.springframework.beans.factory.annotation.Autowired

import com.fasterxml.jackson.databind.JsonNode

import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraApplication
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.service.internal.AuroraVersioningException

class DeployBundleServiceTest extends AbstractMockedOpenShiftSpecification {

  public static final String ENV_NAME = "secrettest"
  public static final String APP_NAME = "aos-simple"
  final ApplicationId aid = new ApplicationId(ENV_NAME, APP_NAME)
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
}
