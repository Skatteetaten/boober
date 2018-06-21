package no.skatteetaten.aurora.boober.contracts

import no.skatteetaten.aurora.boober.controller.v1.VaultControllerV1
import no.skatteetaten.aurora.boober.service.vault.EncryptedFileVault
import no.skatteetaten.aurora.boober.service.vault.VaultService
import no.skatteetaten.aurora.boober.service.vault.VaultWithAccess

class VaultBase extends AbstractContractBase {

  void setup() {
    loadJsonResponses(this)
    def vaultName = response('vaults', '$.items[0].name', String)
    VaultWithAccess vault = new VaultWithAccess(createVault(), vaultName)
    def vaultService = Mock(VaultService) {
      findAllVaultsWithUserAccessInVaultCollection(_ as String) >> [vault]
      findVault(_ as String, _ as String) >> createVault()
      findFileInVault(_ as String, _ as String, _ as String) >> 'test'.bytes
      'import'(_ as String, _ as String, _ as List, _ as Map) >> createVault()
      deleteFileInVault(_ as String, _ as String, _ as String) >> createVault()
    }
    VaultControllerV1 controller = new VaultControllerV1(vaultService, true)
    setupMockMvc(controller)
  }

  static EncryptedFileVault createVault() {
    EncryptedFileVault.createFromFolder(new File('./src/test/resources/samples/config/secret'))
  }

}
