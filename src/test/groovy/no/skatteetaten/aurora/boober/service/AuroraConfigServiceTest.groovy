package no.skatteetaten.aurora.boober.service

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import no.skatteetaten.aurora.AuroraMetrics
import no.skatteetaten.aurora.boober.controller.security.User

class AuroraConfigServiceTest extends AbstractSpec {

  static REMOTE_REPO_FOLDER = new File("build/gitrepos_auroraconfig_bare").absoluteFile.absolutePath
  static CHECKOUT_PATH = new File("build/auroraconfigs").absoluteFile.absolutePath
  static AURORA_CONFIG_NAME = "aos"

  def userDetailsProvider = Mock(UserDetailsProvider)
  def auroraMetrics = new AuroraMetrics(new SimpleMeterRegistry())
  def gitService = new GitService(userDetailsProvider, "$REMOTE_REPO_FOLDER/%s", CHECKOUT_PATH, "", "", auroraMetrics)
  def auroraConfigService = new AuroraConfigService(gitService)

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

  def "Finds existnig AuroraConfig by name"() {
    when:
      def auroraConfig = auroraConfigService.findAuroraConfig(AURORA_CONFIG_NAME)

    then:
      auroraConfig != null
      auroraConfig.auroraConfigFiles.size() == 0
  }
}
