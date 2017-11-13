package no.skatteetaten.aurora.boober.facade

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Metrics
import io.micrometer.spring.autoconfigure.export.StringToDurationConverter
import no.skatteetaten.aurora.AuroraMetrics
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraPermissions
import no.skatteetaten.aurora.boober.model.AuroraSecretVault
import no.skatteetaten.aurora.boober.service.AuroraVersioningException
import no.skatteetaten.aurora.boober.service.EncryptionService
import no.skatteetaten.aurora.boober.service.GitService
import no.skatteetaten.aurora.boober.service.GitServiceHelperKt
import no.skatteetaten.aurora.boober.service.SecretVaultPermissionService
import no.skatteetaten.aurora.boober.service.SecretVaultService
import spock.lang.Specification
import spock.mock.DetachedMockFactory

@SpringBootTest(classes = [
    no.skatteetaten.aurora.boober.Configuration,
    VaultFacade,
    GitService,
    EncryptionService,
    SecretVaultService,
    Config,
    AuroraMetrics
])
class VaultFacadeTest extends Specification {

  public static final String ENV_NAME = "secrettest"
  public static final String APP_NAME = "aos-simple"
  final ApplicationId aid = new ApplicationId(ENV_NAME, APP_NAME)

  @Configuration
  @Import(StringToDurationConverter.class)
  static class Config {
    private DetachedMockFactory factory = new DetachedMockFactory()

    @Bean
    MeterRegistry meterRegistry() {
      Metrics.globalRegistry
    }

    @Bean
    UserDetailsProvider userDetailsProvider() {
      factory.Mock(UserDetailsProvider)
    }

    @Bean
    SecretVaultPermissionService secretPermissionService() {
      factory.Mock(SecretVaultPermissionService)
    }
  }

  @Autowired
  VaultFacade facade

  @Autowired
  UserDetailsProvider userDetailsProvider

  @Autowired
  GitService gitService

  @Autowired
  SecretVaultPermissionService permissionService

  private Git createRepoAndSaveFiles(String affiliation, AuroraSecretVault vault) {
    userDetailsProvider.authenticatedUser >> new User("test", "", "Test Foo")
    facade.save(affiliation, vault, false)
    return gitService.openRepo(affiliation)
  }

  def affiliation = "aos"
  def vaultName = "foo"

  def secretFile = "latest.properties"
  def secret = ['latest.properties': "FOO=BAR"]
  def vault = new AuroraSecretVault(vaultName, secret, null, [:])

  def git

  def setup() {
    gitService.deleteFiles(affiliation)
    GitServiceHelperKt.createInitRepo(affiliation)
  }

  def cleanup() {
    if (git != null) {
      gitService.closeRepository(git)
    }
  }

  def "Should successfully save secrets to git"() {
    given:
      permissionService.hasUserAccess(_) >> true

    when:
      git = createRepoAndSaveFiles(affiliation, vault)
      def gitLog = git.log().call().head()

    then:
      gitLog.authorIdent.name == "Test Foo"
      TreeWalk tw = new TreeWalk(git.getRepository())
      def tree = tw.addTree(gitLog.tree)
      tw.setRecursive(true)
      tw.setFilter(PathFilter.create(".secret/foo/latest.properties"))
      tw.next()
  }

  def "Should not allow users with no access to  save secrets to git"() {
    given:
      permissionService.hasUserAccess(_) >> false

    when:
      git = createRepoAndSaveFiles(affiliation, vault)
      git.log().call().head()

    then:
      thrown(IllegalAccessError)

  }

  def "Should not allow overwrite of changed secret with old version set"() {
    given:
      permissionService.hasUserAccess(_) >> true
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
      permissionService.hasUserAccess(_) >> true
      createRepoAndSaveFiles(affiliation, vault)

    when:
      def newVault = new AuroraSecretVault(vaultName, secret, null, [:])
      def result = facade.save(affiliation, newVault, false)

    then:
      result.name == "foo"

  }

  def "Should not reencrypt unchanged secrets"() {
    given:
      permissionService.hasUserAccess(_) >> true
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
      permissionService.hasUserAccess(_) >> true
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

      permissionService.hasUserAccess(_) >> true
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
      permissionService.hasUserAccess(_) >> true
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
      permissionService.hasUserAccess(_) >> true
      createRepoAndSaveFiles(affiliation, vault)

    when:
      facade.delete(affiliation, vaultName)

    then:
      facade.find(affiliation, vaultName).secrets.isEmpty()
  }

  def "Should not remove other vaults when adding a new"() {
    given:
      permissionService.hasUserAccess(_) >> true
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
      permissionService.hasUserAccess(_) >> true
      createRepoAndSaveFiles(affiliation, vault)
      def newVault = new AuroraSecretVault("vault2", secret)
      facade.save(affiliation, newVault, false)

    when:

      def vaults = facade.listAllVaultsWithUserAccess(affiliation)


    then:
      def vaultNames = vaults.collect { it.name }
      vaultNames.contains("vault2")
  }

  def "Should show that you cannot admin vault"() {
    given:
      def opsGroup = new AuroraPermissions(["TEAM_OPS"])
      def devGroup = new AuroraPermissions(["TEAM_DEV"])
      _ * permissionService.hasUserAccess(null) >> true
      _ * permissionService.hasUserAccess(devGroup) >> true

      createRepoAndSaveFiles(affiliation, vault)

      // Only temporarily grant ops access to allow vault to be saved
      1 * permissionService.hasUserAccess(opsGroup) >> true
      facade.save(affiliation, new AuroraSecretVault("vault2", secret, opsGroup), false)
      facade.save(affiliation, new AuroraSecretVault("vault3", secret, devGroup), false)

      _ * permissionService.hasUserAccess(opsGroup) >> false

    when:
      def vaults = facade.listAllVaultsWithUserAccess(affiliation)


    then:
      vaults.size() == 3
      vaults.any { (!it.admin && it.name == "vault2") }

  }
}
