package no.skatteetaten.aurora.boober.contracts

import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.controller.v1.AuroraDeploymentSpecControllerV1
import no.skatteetaten.aurora.boober.controller.v1.AuroraDeploymentSpecResponder
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecService

class AuroradeploymentspecBase extends AbstractContractBase {

  void setup() {
    loadJsonResponses(this)
    def auroraDeploymentSpecService = Mock(AuroraDeploymentSpecService) {
      getAuroraDeploymentSpec(_, _, _, _) >> new AuroraDeploymentSpec([:])
    }
    def responseFactory = Mock(AuroraDeploymentSpecResponder) {
      create(_) >> responseObject('deploymentspec-formatted', Response)
      create(_, _) >> responseObject('deploymentspec', Response)
    }

    AuroraDeploymentSpecControllerV1 controller = new AuroraDeploymentSpecControllerV1(auroraDeploymentSpecService,
        responseFactory)
    setupMockMvc(controller)
  }
}
