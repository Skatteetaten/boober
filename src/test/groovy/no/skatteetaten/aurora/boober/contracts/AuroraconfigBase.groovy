package no.skatteetaten.aurora.boober.contracts

import no.skatteetaten.aurora.boober.controller.v1.AuroraConfigControllerV1
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.AuroraConfigService

class AuroraconfigBase extends AbstractContractBase {

  void setup() {
    loadJsonResponses(this)
    def auroraConfigService = Mock(AuroraConfigService) {
      findAuroraConfig(_ as AuroraConfigRef) >> createAuroraConfig()
      findAuroraConfigFileNames(_ as AuroraConfigRef) >> createFileNames()
      findAuroraConfigFile(_ as AuroraConfigRef, _ as String) >> createAuroraConfigFile()
      updateAuroraConfigFile(_ as AuroraConfigRef, _ as String, _ as String, null) >> createAuroraConfig()
      patchAuroraConfigFile(_ as AuroraConfigRef, _ as String, _ as String, null) >> createAuroraConfig()
    }
    AuroraConfigControllerV1 controller = new AuroraConfigControllerV1(auroraConfigService)
    setupMockMvc(controller)
  }

  AuroraConfigFile createAuroraConfigFile() {
    def files = response('auroraconfig', '$.items[0].files[0]', Map)
    new AuroraConfigFile(files.name, files.contents, false, false)
  }

  AuroraConfig createAuroraConfig() {
    def affiliation = response('auroraconfig', '$.items[0].name', String)
    new AuroraConfig([createAuroraConfigFile()], affiliation, "master")
  }

  List<String> createFileNames() {
    (List) response('filenames', '$.items', List)
  }
}
