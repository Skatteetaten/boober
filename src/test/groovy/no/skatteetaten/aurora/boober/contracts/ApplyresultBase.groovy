package no.skatteetaten.aurora.boober.contracts

import java.time.Instant

import no.skatteetaten.aurora.boober.controller.v1.ApplyResultController
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.AuroraDeployResult
import no.skatteetaten.aurora.boober.service.DeployHistory
import no.skatteetaten.aurora.boober.service.DeployLogService
import no.skatteetaten.aurora.boober.service.Deployer

class ApplyresultBase extends AbstractContractBase {

  void setup() {
    loadJsonResponses(this)
    def deployLogService = Mock(DeployLogService) {
      deployHistory(_ as AuroraConfigRef) >> []
      findDeployResultById(_ as AuroraConfigRef, _ as String) >>
          { arguments -> (arguments[1] == 'invalid-id') ? null : createDeployResult() }
    }
    ApplyResultController controller = new ApplyResultController(deployLogService)
    setupMockMvc(controller)
  }

  DeployHistory createDeployResult() {
    def ident = response('deployresult', '$.items[0].deployer', Map)
    def time = response('deployresult', '$.items[0].time', String)
    new DeployHistory(
        new Deployer(ident.name, ident.email),
        Instant.parse(time),
        new AuroraDeployResult(null, "", [], true, false, null, null, false, null),
        new AuroraConfigRef("", "", ""))
  }
}
