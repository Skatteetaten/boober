package no.skatteetaten.aurora.boober.contracts

import no.skatteetaten.aurora.boober.controller.v1.ClientConfigControllerV1

class ClientconfigBase extends AbstractContractBase {

  void setup() {
    loadResponseJson('clientconfig')
    ClientConfigControllerV1 controller = new ClientConfigControllerV1(
        jsonPath('$.items[0].gitUrlPattern'),
        jsonPath('$.items[0].openshiftCluster'),
        jsonPath('$.items[0].openshiftUrl'))
    setupMockMvc(controller)
  }

}
