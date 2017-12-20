package no.skatteetaten.aurora.boober.service

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import no.skatteetaten.aurora.AuroraMetrics
import spock.lang.Specification

class VaultServiceTest extends Specification {

  def auroraMetrics = new AuroraMetrics(new SimpleMeterRegistry())

  def userDetailsProvider = Mock(UserDetailsProvider)

  def gitService = new GitService(userDetailsProvider, "/tmp/boobertest/%s", "build", "", "", auroraMetrics)

  def permissionService = Mock(PermissionService)

  def vaultService = new VaultService(gitService, Mock(EncryptionService), permissionService)

  static COLLECTION_NAME = "paas"

  def setupSpec() {
    GitServiceHelperKt.createInitRepo(COLLECTION_NAME)
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
      def secret = "SERVICE_PASSWORD=FOO"
      permissionService.hasUserAccess(_) >> true

    when:
      def vault = vaultService.updateSecretFile(COLLECTION_NAME, "test", "passwords.properties", secret, "", false)

    then:
      vault != null
      println vault
  }
}
