package no.skatteetaten.aurora.boober.contracts

import org.eclipse.jgit.lib.PersonIdent

import com.fasterxml.jackson.databind.node.NullNode

import no.skatteetaten.aurora.boober.controller.v1.ApplyResultController
import no.skatteetaten.aurora.boober.service.DeployHistory
import no.skatteetaten.aurora.boober.service.DeployLogService

class ApplyresultBase extends AbstractContractBase {

  void setup() {
    loadJsonResponses('applyresult')
    def deployLogService = Mock(DeployLogService) {
      deployHistory(_ as String) >> []
      findDeployResultById(_ as String, _ as String) >> { arguments -> (arguments[1] == 'invalid-id') ? null : createDeployResult() }
    }
    ApplyResultController controller = new ApplyResultController(deployLogService)
    setupMockMvc(controller)
  }

  DeployHistory createDeployResult() {
    def ident = response('deployresult', '$.items[0].ident', Map)
    new DeployHistory(new PersonIdent(ident.name, ident.emailAddress), NullNode.instance)
  }
}
