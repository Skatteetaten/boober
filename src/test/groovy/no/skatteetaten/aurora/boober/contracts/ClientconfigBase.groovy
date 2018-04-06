package no.skatteetaten.aurora.boober.contracts

import no.skatteetaten.aurora.boober.controller.v1.ClientConfigControllerV1

class ClientconfigBase extends AbstractContractBase {

  void setup() {
    loadJsonResponses('clientconfig')
    def item = response('$.items[0]', Map)
    ClientConfigControllerV1 controller = new ClientConfigControllerV1(
        item.gitUrlPattern,
        item.openshiftCluster,
        item.openshiftUrl)
    setupMockMvc(controller)
  }

}
