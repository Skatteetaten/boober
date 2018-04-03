package no.skatteetaten.aurora.boober.contracts

import static org.mockito.Matchers.anyString

import org.eclipse.jgit.lib.PersonIdent

import com.fasterxml.jackson.databind.node.NullNode

import no.skatteetaten.aurora.boober.controller.v1.ApplyResultController
import no.skatteetaten.aurora.boober.service.DeployHistory
import no.skatteetaten.aurora.boober.service.DeployLogService

class ApplyresultBase extends AbstractContractBase {

  void setup() {
    loadJsonResponses('applyresult')
    def deployLogService = Mock(DeployLogService) {
      deployHistory(anyString()) >> []
      findDeployResultById('aos', '123') >> createDeployResult()
    }
    ApplyResultController controller = new ApplyResultController(deployLogService)
    setupMockMvc(controller)
  }

  DeployHistory createDeployResult() {
    def ident = responseObject('deployresult', '$.items[0].ident')
    new DeployHistory(new PersonIdent(ident.name, ident.emailAddress), NullNode.instance)
  }
}
