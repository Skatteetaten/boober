package no.skatteetaten.aurora.boober.contracts

import java.time.Instant

import com.fasterxml.jackson.databind.node.NullNode

import no.skatteetaten.aurora.boober.controller.v1.ApplyResultController
import no.skatteetaten.aurora.boober.service.DeployHistory
import no.skatteetaten.aurora.boober.service.DeployLogService
import no.skatteetaten.aurora.boober.service.Deployer

class ApplyresultBase extends AbstractContractBase {

  void setup() {
    loadJsonResponses(this)
    def deployLogService = Mock(DeployLogService) {
      deployHistory(_ as String) >> []
      findDeployResultById(_ as String, _ as String) >>
          { arguments -> (arguments[1] == 'invalid-id') ? null : createDeployResult() }
    }
    ApplyResultController controller = new ApplyResultController(deployLogService)
    setupMockMvc(controller)
  }

  DeployHistory createDeployResult() {
    def ident = response('deployresult', '$.items[0].deployer', Map)
    //TODO: Fetch epoch from response here?
    new DeployHistory(new Deployer(ident.name, ident.email), Instant.EPOCH, NullNode.instance)
  }
}
