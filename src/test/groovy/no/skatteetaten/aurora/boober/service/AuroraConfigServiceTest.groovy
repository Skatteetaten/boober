package no.skatteetaten.aurora.boober.service

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import no.skatteetaten.aurora.AuroraMetrics
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.model.AbstractAuroraConfigTest
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraVersioningException

class AuroraConfigServiceTest extends AbstractAuroraConfigTest {

  static REMOTE_REPO_FOLDER = new File("build/gitrepos_auroraconfig_bare").absoluteFile.absolutePath
  static CHECKOUT_PATH = new File("build/auroraconfigs").absoluteFile.absolutePath
  static AURORA_CONFIG_NAME = AFFILIATION
  def aid = DEFAULT_AID

  def userDetailsProvider = Mock(UserDetailsProvider)
  def auroraMetrics = new AuroraMetrics(new SimpleMeterRegistry())
  def gitService = new GitService(userDetailsProvider, "$REMOTE_REPO_FOLDER/%s", CHECKOUT_PATH, "", "", auroraMetrics)
  def auroraConfigService = new AuroraConfigService(gitService, Mock(BitbucketProjectService), Mock(AuroraDeploymentSpecValidator), 6)

  def setup() {
    GitServiceHelperKt.recreateRepo(new File(REMOTE_REPO_FOLDER, "${AURORA_CONFIG_NAME}.git"))
    GitServiceHelperKt.recreateFolder(new File(CHECKOUT_PATH))

    userDetailsProvider.getAuthenticatedUser() >> new User("aurora", "token", "Aurora Test User", [])
  }

  def "Throws exception when AuroraConfig cannot be found"() {
    when:
      auroraConfigService.findAuroraConfig("no_such_auroraconfig")

    then:
      thrown(IllegalArgumentException)
  }

  def "Finds existing AuroraConfig by name"() {
    when:
      def auroraConfig = auroraConfigService.findAuroraConfig(AURORA_CONFIG_NAME)

    then:
      auroraConfig != null
      auroraConfig.auroraConfigFiles.size() == 0
  }

  def "Should update one file in AuroraConfig"() {
    given:
      def auroraConfig = createAuroraConfig(defaultAuroraConfig())
      auroraConfigService.save(auroraConfig)

      def fileToChange = "utv/aos-simple.json"
      AuroraConfigFile theFileToChange = auroraConfig.auroraConfigFiles.find { it.name == fileToChange }

    when:
      auroraConfigService.
          updateAuroraConfigFile(AURORA_CONFIG_NAME, fileToChange, '{"version": "1.0.0"}', theFileToChange.version)

    then:
      def git = gitService.checkoutRepository(AURORA_CONFIG_NAME)
      def gitLog = git.log().call().head()
      git.close()
      gitLog.authorIdent.name == "Aurora Test User"
      gitLog.fullMessage == "Added: 0, Modified: 1, Deleted: 0"
  }

  def "Save AuroraConfig"() {

    when:
      def auroraConfig = auroraConfigService.findAuroraConfig(AURORA_CONFIG_NAME)

    then:
      auroraConfig.auroraConfigFiles.size() == 0

    when:
      auroraConfig = createAuroraConfig(defaultAuroraConfig())
      auroraConfigService.save(auroraConfig)

    and:
      GitServiceHelperKt.recreateFolder(new File(CHECKOUT_PATH))
      auroraConfig = auroraConfigService.findAuroraConfig(AURORA_CONFIG_NAME)

    then:
      auroraConfig.auroraConfigFiles.size() == 4
      auroraConfig.auroraConfigFiles
          .collect() { it.name }
          .containsAll(["about.json", "utv/about.json", "utv/aos-simple.json", "aos-simple.json"])
  }

  def "Delete file from AuroraConfig"() {

    given:
      def auroraConfig = createAuroraConfig(defaultAuroraConfig())
      auroraConfigService.save(auroraConfig)

    when:
      auroraConfig = createAuroraConfig([
          "about.json"    : DEFAULT_ABOUT,
          "utv/about.json": DEFAULT_UTV_ABOUT
      ])
      auroraConfigService.save(auroraConfig)

    and:
      GitServiceHelperKt.recreateFolder(new File(CHECKOUT_PATH))
      auroraConfig = auroraConfigService.findAuroraConfig(AURORA_CONFIG_NAME)

    then:
      auroraConfig.auroraConfigFiles.size() == 2
      auroraConfig.auroraConfigFiles
          .collect() { it.name }
          .containsAll(["about.json", "utv/about.json"])
  }

  def "Should not update one file in AuroraConfig if version is wrong"() {
    given:
      def fileToChange = "${aid.environment}/${aid.application}.json"
      def auroraConfig = createAuroraConfig(defaultAuroraConfig())
      auroraConfigService.save(auroraConfig)

    when:
      auroraConfigService.
          updateAuroraConfigFile(AURORA_CONFIG_NAME, fileToChange, '{"version": "1.0.0"}', "incorrect hash")

    then:
      def e = thrown(AuroraVersioningException)
      e.errors.size() == 1
  }
}
