package no.skatteetaten.aurora.boober.service

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import no.skatteetaten.aurora.AuroraMetrics
import spock.lang.Specification

class VaultServiceTest extends Specification {

  static REMOTE_REPO_FOLDER = new File("build/gitrepos").absoluteFile.absolutePath
  static CHECKOUT_PATH = new File("build/vaults").absoluteFile.absolutePath

  def auroraMetrics = new AuroraMetrics(new SimpleMeterRegistry())

  def userDetailsProvider = Mock(UserDetailsProvider)

  def gitService = new GitService(userDetailsProvider, "$REMOTE_REPO_FOLDER/%s", CHECKOUT_PATH, "", "", auroraMetrics)

  def encryptionService = Mock(EncryptionService)

  def permissionService = Mock(PermissionService)

  def vaultService = new VaultService(gitService, encryptionService, permissionService)

  static COLLECTION_NAME = "paas"

  def setupSpec() {
    GitServiceHelperKt.createInitRepo(COLLECTION_NAME)
    GitServiceHelperKt.recreateFolder(new File(CHECKOUT_PATH))
  }

  def "Find vault"() {

    when:
      def vaultCollection = vaultService.findVaultCollection(COLLECTION_NAME)

    then:
      vaultCollection != null
      vaultCollection.vaults.size() == 0
  }

  def "Update secret"() {

    given:
      def vaultName = "test"
      def fileName = "passwords.properties"
      def secret = "SERVICE_PASSWORD=FOO"
      permissionService.hasUserAccess(_) >> true
      _ * encryptionService.decrypt(_) >> { it[0] }
      _ * encryptionService.encrypt(_) >> { it[0] }

    when:
      def vault = vaultService.updateFileInVault(COLLECTION_NAME, vaultName, fileName, secret)

    then:
      vault != null
      vault.secrets[fileName] == secret
  }
}
