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

  static VAULT_NAME = "test"

  def setup() {
    GitServiceHelperKt.createInitRepo(COLLECTION_NAME)
    GitServiceHelperKt.recreateFolder(new File(CHECKOUT_PATH))

    permissionService.hasUserAccess(_) >> true

    // No encryption/decryption
    _ * encryptionService.decrypt(_) >> { it[0] }
    _ * encryptionService.encrypt(_) >> { it[0] }
  }

  def "Find vault"() {

    when:
      def vaultCollection = vaultService.findVaultCollection(COLLECTION_NAME)

    then:
      vaultCollection != null
      vaultCollection.vaults.size() == 0
  }

  def "Update file"() {

    given:
      def fileName = "passwords.properties"
      def contents = "SERVICE_PASSWORD=FOO"

    when:
      def vault = vaultService.createOrUpdateFileInVault(COLLECTION_NAME, VAULT_NAME, fileName, contents)

    then:
      vault.secrets.size() == 1
      vault.secrets[fileName] == contents

    and: "Lets make sure the contents was actually saved"
      GitServiceHelperKt.recreateFolder(new File(CHECKOUT_PATH))

    when:
      def vaultCollection = vaultService.findVaultCollection(COLLECTION_NAME)
      vault = vaultCollection.findVaultByName(VAULT_NAME)

    then:
      vault.secrets.size() == 1
      vault.secrets[fileName] == contents
  }

  def "Delete file"() {

    given: "A vault with some files"
      def fileName = "passwords.properties"
      def contents = "SERVICE_PASSWORD=FOO"
      def vault = vaultService.createOrUpdateFileInVault(COLLECTION_NAME, VAULT_NAME, fileName, contents)
      vaultService.createOrUpdateFileInVault(COLLECTION_NAME, VAULT_NAME, "passwords2.properties", contents)

      assert vault.secrets.size() == 2
      assert vault.secrets[fileName] == contents

    when:
      vaultService.deleteFileInVault(COLLECTION_NAME, VAULT_NAME, fileName)

    then:
      vault.secrets.size() == 1

    and: "Lets make sure the secret was actually deleted"
      GitServiceHelperKt.recreateFolder(new File(CHECKOUT_PATH))

    when:
      def vaultCollection = vaultService.findVaultCollection(COLLECTION_NAME)
      vault = vaultCollection.findVaultByName(VAULT_NAME)

    then:
      vault.secrets.size() == 1
  }

  def "Delete vault"() {

    given:
      def fileName = "passwords.properties"
      def contents = "SERVICE_PASSWORD=FOO"
      def vault = vaultService.createOrUpdateFileInVault(COLLECTION_NAME, VAULT_NAME, fileName, contents)

      vault.secrets.size() == 1
      vault.secrets[fileName] == contents

    when:
      vaultService.deleteVault(COLLECTION_NAME, VAULT_NAME)
      vaultService.findVault(COLLECTION_NAME, VAULT_NAME)

    then:
      thrown(IllegalArgumentException)

    and: "Lets make sure the vault was actually deleted"
      GitServiceHelperKt.recreateFolder(new File(CHECKOUT_PATH))

    when:
      vaultService.findVault(COLLECTION_NAME, VAULT_NAME) == null

    then:
      thrown(IllegalArgumentException)
  }
}
