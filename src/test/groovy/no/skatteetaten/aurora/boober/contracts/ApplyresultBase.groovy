package no.skatteetaten.aurora.boober.contracts

import static org.mockito.Matchers.anyString

import no.skatteetaten.aurora.boober.controller.v1.ApplyResultController
import no.skatteetaten.aurora.boober.service.DeployLogService

class ApplyresultBase extends AbstractContractBase {

  void setup() {
    loadResponseJson('applyresult', 'deployhistory')
    def deployLogService = Mock(DeployLogService) {
      deployHistory(anyString()) >> []
    }
    ApplyResultController controller = new ApplyResultController(deployLogService)
    setupMockMvc(controller)
  }
}
