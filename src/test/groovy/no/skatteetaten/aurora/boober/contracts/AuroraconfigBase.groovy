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
      findAuroraConfigFile(_ as String, _ as String) >> createAuroraConfigFile()
      updateAuroraConfigFile(_ as String, _ as String, _ as String, null) >> createAuroraConfig()
    }
    AuroraConfigControllerV1 controller = new AuroraConfigControllerV1(auroraConfigService)
    setupMockMvc(controller)
  }

  AuroraConfigFile createAuroraConfigFile() {
    def files = responseMap('auroraconfig', '$.items[0].files[0]')
    new AuroraConfigFile(files.name, files.contents, false)
  }

  AuroraConfig createAuroraConfig() {
    def affiliation = responseString('auroraconfig', '$.items[0].name')
    new AuroraConfig([createAuroraConfigFile()], affiliation)
  }

  List<String> createFileNames() {
    response('filenames', '$.items', List)
  }
}
