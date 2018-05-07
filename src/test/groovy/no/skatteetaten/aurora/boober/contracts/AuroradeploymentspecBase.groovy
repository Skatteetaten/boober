package no.skatteetaten.aurora.boober.contracts

import no.skatteetaten.aurora.boober.controller.v1.AuroraDeploymentSpecControllerV1
import no.skatteetaten.aurora.boober.model.AuroraDeployEnvironment
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.Permission
import no.skatteetaten.aurora.boober.model.Permissions
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecService

class AuroradeploymentspecBase extends AbstractContractBase {

  void setup() {
    loadJsonResponses(this)
    def auroraDeploymentSpec = createAuroraDeploymentSpec()
    def auroraDeploymentSpecService = Mock(AuroraDeploymentSpecService) {
      getAuroraDeploymentSpecs(_ as String, _ as List) >> [auroraDeploymentSpec]
      getAuroraDeploymentSpec(_ as String, _ as String, _ as String) >> auroraDeploymentSpec
      getAuroraDeploymentSpecsForEnvironment(_ as String, _ as String) >> [auroraDeploymentSpec]
    }
    AuroraDeploymentSpecControllerV1 controller = new AuroraDeploymentSpecControllerV1(auroraDeploymentSpecService)
    setupMockMvc(controller)
  }

  private AuroraDeploymentSpec createAuroraDeploymentSpec() {
    def deploymentSpecs = response('deploymentspec', '$.items[0]', Map)
    new AuroraDeploymentSpec('', TemplateType.development, '', deploymentSpecs, '', '',
        new AuroraDeployEnvironment('', '',
            new Permissions(new Permission(Collections.emptySet(), Collections.emptySet()), null), null),
        null, null, null, null, null, null, null)
  }
}
