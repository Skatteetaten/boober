package no.skatteetaten.aurora.boober.contracts

import no.skatteetaten.aurora.boober.controller.v1.AuroraConfigNamesControllerV1
import no.skatteetaten.aurora.boober.service.AuroraConfigService

class AuroraconfignamesBase extends AbstractContractBase {

  void setup() {
    loadJsonResponses('auroraconfignames')
    def auroraConfigService = Mock(AuroraConfigService) {
      findAllAuroraConfigNames() >> createFileNames()
    }
    AuroraConfigNamesControllerV1 controller = new AuroraConfigNamesControllerV1(auroraConfigService)
    setupMockMvc(controller)
  }

  List<String> createFileNames() {
    response('$.items', List)
  }
}
