package no.skatteetaten.aurora.boober.service.resourceprovisioning

import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.support.PropertiesLoaderUtils

import no.skatteetaten.aurora.boober.service.vault.EncryptedFileVault
import no.skatteetaten.aurora.boober.service.vault.VaultService
import spock.lang.Specification

class VaultProviderTest extends Specification {

  static COLLECTION_NAME = "paas"
  static VAULT_NAME = "test"

  private VaultService vaultService
  private VaultProvider vaultProvider

  void setup() {

    vaultService = Mock(VaultService) {
      findVault(COLLECTION_NAME, VAULT_NAME) >>
          EncryptedFileVault.createFromFolder(new File('./src/test/resources/samples/config/secret/'))
    }
    vaultProvider = new VaultProvider(vaultService)
  }

  def "Find filtered vault data"() {
    given:
      def requests = [new VaultRequest(COLLECTION_NAME, VAULT_NAME, ['Boober'], [:])]

    when:
      def results = vaultProvider.findVaultData(requests)
      def properties = loadProperties(results)

    then:
      results.vaultIndex.size() == 1
      properties['Boober'] == '1'
  }

  private static Properties loadProperties(VaultResults results) {
    def vaultData = results.getVaultData(VAULT_NAME)
    PropertiesLoaderUtils.loadProperties(new ByteArrayResource(vaultData['latest.properties']))
  }
}
