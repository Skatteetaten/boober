package no.skatteetaten.aurora.boober.contracts

import no.skatteetaten.aurora.boober.controller.v1.DeployControllerV1
import no.skatteetaten.aurora.boober.model.AuroraDeployEnvironment
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.Permission
import no.skatteetaten.aurora.boober.model.Permissions
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.service.AuroraDeployResult
import no.skatteetaten.aurora.boober.service.DeployService

class DeployBase extends AbstractContractBase {

  void setup() {
    loadJsonResponses('deploy')
    def deployService = Mock(DeployService) {
      executeDeploy(_ as String, _ as List, _ as List, _ as Boolean) >> [createAuroraDeployResult()]
    }
    DeployControllerV1 controller = new DeployControllerV1(deployService)
    setupMockMvc(controller)
  }

  AuroraDeployResult createAuroraDeployResult() {
    def spec = new AuroraDeploymentSpec('', TemplateType.development, '', [:], '', '',
        new AuroraDeployEnvironment('', '',
            new Permissions(new Permission(Collections.emptySet(), Collections.emptySet()), null), null),
        null, null, null, null, null, null)
    return new AuroraDeployResult(spec)
  }
}
