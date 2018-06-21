package no.skatteetaten.aurora.boober.service.vault

import org.springframework.security.core.authority.SimpleGrantedAuthority

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import no.skatteetaten.aurora.AuroraMetrics
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.service.EncryptionService
import no.skatteetaten.aurora.boober.service.FolderHelperKt
import no.skatteetaten.aurora.boober.service.GitService
import no.skatteetaten.aurora.boober.service.GitServiceHelperKt
import no.skatteetaten.aurora.boober.service.UnauthorizedAccessException
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import spock.lang.Specification

class VaultServiceTest extends Specification {

  static REMOTE_REPO_FOLDER = new File("build/gitrepos_vault_bare").absoluteFile.absolutePath
  static CHECKOUT_PATH = new File("build/vaults").absoluteFile.absolutePath

  def auroraMetrics = new AuroraMetrics(new SimpleMeterRegistry())

  def userDetailsProvider = Mock(UserDetailsProvider)

  def gitService = new GitService(userDetailsProvider, "$REMOTE_REPO_FOLDER/%s", CHECKOUT_PATH, "", "", auroraMetrics)

  def encryptionService = Mock(EncryptionService)

  def vaultService = new VaultService(gitService, encryptionService, userDetailsProvider)

  static COLLECTION_NAME = "paas"

  static VAULT_NAME = "test"

  def setup() {
    GitServiceHelperKt.recreateRepo(new File(REMOTE_REPO_FOLDER, "${COLLECTION_NAME}.git"))

    userDetailsProvider.getAuthenticatedUser() >>
        new User("aurora", "token", "Aurora Test User", [new SimpleGrantedAuthority("UTV")])
    // No encryption/decryption
    _ * encryptionService.decrypt(_) >> { it[0].bytes }
    _ * encryptionService.encrypt(_) >> { new String(it[0]) }
  }

  def "Find vault collection"() {

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
      def vault = vaultService.createOrUpdateFileInVault(COLLECTION_NAME, VAULT_NAME, fileName, contents.bytes)

    then:
      vault.secrets.size() == 1
      vault.secrets[fileName] == contents.bytes

    and: "Lets make sure the contents was actually saved"
      FolderHelperKt.recreateFolder(new File(CHECKOUT_PATH))

    when:
      def vaultCollection = vaultService.findVaultCollection(COLLECTION_NAME)
      vault = vaultCollection.findVaultByName(VAULT_NAME)

    then:
      vault.secrets.size() == 1
      vault.secrets[fileName] == contents.bytes
  }

  def "Secret vault keys must have correct name"() {

    given:
      def contents = ["latest.properties": "INVALID KEY=FOO".bytes]

    when:
      vaultService.import(COLLECTION_NAME, VAULT_NAME, [], contents)

    then:
      def e = thrown(IllegalArgumentException)
      e.message == "Vault key=[latest.properties/INVALID KEY] is not valid. Regex used for matching ^[-._a-zA-Z0-9]+\$"
  }

  def "Verify secret file invalid lines"() {
    when:
      VaultService.assertSecretKeysAreValid(["latest.properties": line.bytes])
    then:
      thrown(IllegalArgumentException)
    where:
      line << [
          "SOME-KEY = SOME VALUE",
          " SOME-KEY=SOME VALUE",
          " SOME-KEY = SOME VALUE"
      ]
  }

  def "Verify secret file valid lines"() {
    expect:
      VaultService.assertSecretKeysAreValid(["latest.properties": line.bytes])
    where:
      line << [
          "SOME_KEY=SOME VALUE",
          "SOME-KEY=SOME VALUE"
      ]
  }

  def "Delete file"() {

    given: "A vault with some files"
      def fileName = "passwords.properties"
      def contents = "SERVICE_PASSWORD=FOO"
      def vault = vaultService.createOrUpdateFileInVault(COLLECTION_NAME, VAULT_NAME, fileName, contents.bytes)
      vaultService.createOrUpdateFileInVault(COLLECTION_NAME, VAULT_NAME, "passwords2.properties", contents.bytes)

      assert vault.secrets.size() == 2
      assert vault.secrets[fileName] == contents.bytes

    when:
      vaultService.deleteFileInVault(COLLECTION_NAME, VAULT_NAME, fileName)

    then:
      vault.secrets.size() == 1

    and: "Lets make sure the secret was actually deleted"
      FolderHelperKt.recreateFolder(new File(CHECKOUT_PATH))

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
      def vault = vaultService.createOrUpdateFileInVault(COLLECTION_NAME, VAULT_NAME, fileName, contents.bytes)

      vault.secrets.size() == 1
      vault.secrets[fileName] == contents.bytes

    when:
      vaultService.deleteVault(COLLECTION_NAME, VAULT_NAME)
      vaultService.findVault(COLLECTION_NAME, VAULT_NAME)

    then:
      thrown(IllegalArgumentException)

    and: "Lets make sure the vault was actually deleted"
      // GitServiceHelperKt.recreateFolder(new File(CHECKOUT_PATH))

    when:
      vaultService.findVault(COLLECTION_NAME, VAULT_NAME) == null

    then:
      thrown(IllegalArgumentException)
  }

  def "Updates permissions"() {

    given: "A vault with some files"
      def fileName = "passwords.properties"
      def contents = "SERVICE_PASSWORD=FOO"
      def vault = vaultService.createOrUpdateFileInVault(COLLECTION_NAME, VAULT_NAME, fileName, contents.bytes)

    expect:
      !vault.permissions

    when:
      vaultService.setVaultPermissions(COLLECTION_NAME, VAULT_NAME, ["UTV"])
      vault = vaultService.findVault(COLLECTION_NAME, VAULT_NAME)

    then:
      vault.permissions == ["UTV"]
  }

  def "Get vault when user has no access should throw UnauthorizedAccessException"() {
    given: "A vault with some files"
      def fileName = "passwords.properties"
      def contents = "SERVICE_PASSWORD=FOO"
      vaultService.createOrUpdateFileInVault(COLLECTION_NAME, VAULT_NAME, fileName, contents.bytes)
      vaultService.setVaultPermissions(COLLECTION_NAME, VAULT_NAME, [])

    when:
      def vault = vaultService.findVault(COLLECTION_NAME, VAULT_NAME)
      vault.permissions = ["admin"]
      vaultService.findVault(COLLECTION_NAME, VAULT_NAME)

    then:
      thrown(UnauthorizedAccessException)

    and:
      def vaults = vaultService.findAllVaultsWithUserAccessInVaultCollection(COLLECTION_NAME)
      def vaultWithAccess = vaults.find { it.vaultName == VAULT_NAME }

    then:
      vaultWithAccess != null
      !vaultWithAccess.hasAccess
      !vaultWithAccess.vault
  }

  def "Set vault permissions when user are not in any group should throw UnauthorizedAccessException"() {
    when:
      vaultService.setVaultPermissions(COLLECTION_NAME, VAULT_NAME, ["admin"])

    then:
      def e = thrown(UnauthorizedAccessException)
      e.message == "You (aurora) do not have required permissions ([admin]) to operate on this vault. You have [UTV]"
  }

  def "Set vault permissions when user are in one or more groups should update vault permissions"() {
    given:
      def fileName = "passwords.properties"
      def contents = "SERVICE_PASSWORD=FOO"
      vaultService.createOrUpdateFileInVault(COLLECTION_NAME, VAULT_NAME, fileName, contents.bytes)

    when:
      vaultService.setVaultPermissions(COLLECTION_NAME, VAULT_NAME, ["admin", "UTV"])

    then:
      def vault = vaultService.findVault(COLLECTION_NAME, VAULT_NAME)
      vault.permissions == ["admin", "UTV"]
  }

  def "Find secret vault keys"() {
    when:
      def fileName = "latest.properties"
      def contents = "key1=foo\nkey2=bar\nkey3=baz"
      vaultService.createOrUpdateFileInVault(COLLECTION_NAME, VAULT_NAME, fileName, contents.bytes)
      def vaultKeys = vaultService.findVaultKeys(COLLECTION_NAME, VAULT_NAME, fileName)

    then:
      vaultKeys.size() == 3
      vaultKeys.containsAll(['key1', 'key2', 'key3'])
  }
}