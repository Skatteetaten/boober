package no.skatteetaten.aurora.boober.contracts

import no.skatteetaten.aurora.boober.controller.v1.AuroraConfigControllerV1
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.service.AuroraConfigService

class AuroraconfigBase extends AbstractContractBase {

  void setup() {
    loadJsonResponses('auroraconfig')
    def auroraConfigService = Mock(AuroraConfigService) {
      findAuroraConfig(_ as String) >> createAuroraConfig()
      findAuroraConfigFileNames(_ as String) >> createFileNames()
    }
    AuroraConfigControllerV1 controller = new AuroraConfigControllerV1(auroraConfigService)
    setupMockMvc(controller)
  }

  AuroraConfig createAuroraConfig() {
    def files = responseMap('auroraconfig', '$.items[0].files[0]')
    def affiliation = responseString('auroraconfig', '$.items[0].name')
    AuroraConfigFile auroraConfigFile = new AuroraConfigFile(files.name, files.contents, false)
    new AuroraConfig([auroraConfigFile], affiliation)
  }

  List<String> createFileNames() {
    response('filenames', '$.items', List)
  }
}
