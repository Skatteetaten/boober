package no.skatteetaten.aurora.boober.facade

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraPermissions
import no.skatteetaten.aurora.boober.model.AuroraSecretVault
import no.skatteetaten.aurora.boober.service.EncryptionService
import no.skatteetaten.aurora.boober.service.GitService
import no.skatteetaten.aurora.boober.service.GitServiceHelperKt
import no.skatteetaten.aurora.boober.service.SecretVaultService
import no.skatteetaten.aurora.boober.service.internal.AuroraVersioningException
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import spock.lang.Specification
import spock.mock.DetachedMockFactory

@SpringBootTest(classes = [
    no.skatteetaten.aurora.boober.Configuration,
    VaultFacade,
    GitService,
    EncryptionService,
    SecretVaultService,
    Config
], properties = [
    "boober.git.urlPattern=/tmp/boober-test/%s",
    "boober.git.checkoutPath=/tmp/boober",
    "boober.git.username=",
    "boober.git.password="
])
class VaultFacadeTest extends Specification {

  public static final String ENV_NAME = "secrettest"
  public static final String APP_NAME = "aos-simple"
  final ApplicationId aid = new ApplicationId(ENV_NAME, APP_NAME)

  @Configuration
  static class Config {
    private DetachedMockFactory factory = new DetachedMockFactory()

    @Bean
    UserDetailsProvider userDetailsProvider() {
      factory.Mock(UserDetailsProvider)
    }

    @Bean
    OpenShiftClient openshiftClient() {
      factory.Mock(OpenShiftClient)
    }
  }

  @Autowired
  VaultFacade facade

  @Autowired
  UserDetailsProvider userDetailsProvider

  @Autowired
  GitService gitService

  @Autowired
  OpenShiftClient openshift

  private void createRepoAndSaveFiles(String affiliation, AuroraSecretVault vault) {
    GitServiceHelperKt.createInitRepo(affiliation)
    userDetailsProvider.authenticatedUser >> new User("test", "", "Test Foo")
    facade.save(affiliation, vault, false)
  }

  def affiliation = "aos"
  def vaultName = "foo"

  def secretFile = "latest.properties"
  def secret = ['latest.properties': "FOO=BAR"]
  def vault = new AuroraSecretVault(vaultName, secret, null, [:])

  def "Should successfully save secrets to git"() {
    given:
      openshift.hasUserAccess(_, _) >> true

    when:
      createRepoAndSaveFiles(affiliation, vault)
      def git = gitService.checkoutRepoForAffiliation(affiliation)
      def gitLog = git.log().call().head()
      gitService.closeRepository(git)

    then:
      gitLog.authorIdent.name == "Test Foo"
      gitLog.fullMessage == "Added: 1, Modified: 0, Deleted: 0"
  }

  def "Should not allow users with no access to  save secrets to git"() {
    given:
      openshift.hasUserAccess(_, _) >> false

    when:
      createRepoAndSaveFiles(affiliation, vault)
      def git = gitService.checkoutRepoForAffiliation(affiliation)
      def gitLog = git.log().call().head()
      gitService.closeRepository(git)

    then:
      thrown(IllegalAccessError)

  }

  def "Should not allow overwrite of changed secret with old version set"() {
    given:
      openshift.hasUserAccess(_, _) >> true
      createRepoAndSaveFiles(affiliation, vault)

    when:
      def newVault = new AuroraSecretVault(vaultName, secret, null, ["latest.properties": "INVALID_VERSION"])
      facade.save(affiliation, newVault, true)

    then:
      def e = thrown(AuroraVersioningException)
      e.errors.size() == 1
      e.errors[0].fileName == ".secret/foo/latest.properties"
  }

  def "Allow ignore versions if we specify validateVersions=false is true"() {
    given:
      openshift.hasUserAccess(_, _) >> true
      createRepoAndSaveFiles(affiliation, vault)

    when:
      def newVault = new AuroraSecretVault(vaultName, secret, null, [:])
      def result = facade.save(affiliation, newVault, false)

    then:
      result.name == "foo"

  }

  def "Should not reencrypt unchanged secrets"() {
    given:
      openshift.hasUserAccess(_, _) >> true
      createRepoAndSaveFiles(affiliation, vault)
      def storedVault = facade.find(affiliation, vaultName)

    when:
      def newVault = new AuroraSecretVault(vaultName, secret, null, storedVault.versions)

      facade.save(affiliation, newVault, false)
      def updatedAuroraConfig = facade.find(affiliation, vaultName)

    then:
      storedVault.versions[secretFile] == updatedAuroraConfig.versions[secretFile]
  }

  def "Should add secret to vault "() {
    given:
      openshift.hasUserAccess(_, _) >> true
      createRepoAndSaveFiles(affiliation, vault)
      def storedVault = facade.find(affiliation, vaultName)

    when:

      def newSecrets = ['latest.properties': "FOO=BAR", "1.2.3.properties": "BAZ"]

      def newVault = new AuroraSecretVault(vaultName, newSecrets, null, storedVault.versions)

      facade.save(affiliation, newVault, false)
      def updatedAuroraConfig = facade.find(affiliation, vaultName)

    then:
      updatedAuroraConfig.secrets == newSecrets
  }

  def "Should remove secret from vault "() {
    given:
      def newVault = new AuroraSecretVault(vaultName, ['latest.properties': "FOO=BAR", "1.2.3.properties": "BAZ"], null,
          [:])

      openshift.hasUserAccess(_, _) >> true
      createRepoAndSaveFiles(affiliation, newVault)
      def storedVault = facade.find(affiliation, vaultName)

    when:

      def removeVault = new AuroraSecretVault(vaultName, ['latest.properties': "FOO=BAR"], null,
          ['latest.properties': storedVault.versions['latest.properties']])


      facade.save(affiliation, removeVault, false)
      def updatedAuroraConfig = facade.find(affiliation, vaultName)

    then:
      updatedAuroraConfig.secrets.size() == 1
  }

  def "Should update one file in secret"() {
    given:
      openshift.hasUserAccess(_, _) >> true
      createRepoAndSaveFiles(affiliation, vault)
      def storedVault = facade.find(affiliation, vaultName)

    when:

      facade.updateSecretFile(affiliation, vaultName, "latest.properties", "OOOHYEAH",
          storedVault.versions['latest.properties'], true)
      def updatedAuroraConfig = facade.find(affiliation, vaultName)

    then:
      updatedAuroraConfig.versions['latest.properties'] != storedVault.versions['latest.properties']
  }

  def "Should remove vault"() {
    given:
      openshift.hasUserAccess(_, _) >> true
      createRepoAndSaveFiles(affiliation, vault)

    when:
      facade.delete(affiliation, vaultName)

    then:
      facade.find(affiliation, vaultName).secrets.isEmpty()
  }

  def "Should not remove other vaults when adding a new"() {
    given:
      openshift.hasUserAccess(_, _) >> true
      createRepoAndSaveFiles(affiliation, vault)

    when:
      def newVault = new AuroraSecretVault("vault2", secret, null, [:])
      facade.save(affiliation, newVault, false)

      def vault = facade.find(affiliation, vaultName)
      def vault2 = facade.find(affiliation, "vault2")


    then:
      vault.secrets.size() == 1
      vault2.secrets.size() == 1

  }

  def "Should list all vaults"() {
    given:
      openshift.hasUserAccess(_, _) >> true
      createRepoAndSaveFiles(affiliation, vault)
      def newVault = new AuroraSecretVault("vault2", secret)
      facade.save(affiliation, newVault, false)

    when:

      def vaults = facade.listVaults(affiliation)


    then:
      vaults.size() == 2
  }

  def "Should not include vault you cannot admin"() {
    given:
      def permissions = new AuroraPermissions(["UTV"], ["UTV"])
      3 * openshift.hasUserAccess(_, _) >> true
      createRepoAndSaveFiles(affiliation, vault)
      def newVault = new AuroraSecretVault("vault2", secret, permissions)
      facade.save(affiliation, newVault, false)

    when:
      1 * openshift.hasUserAccess("test", permissions) >> false

      def vaults = facade.listVaults(affiliation)


    then:
      vaults.size() == 1
  }
}
