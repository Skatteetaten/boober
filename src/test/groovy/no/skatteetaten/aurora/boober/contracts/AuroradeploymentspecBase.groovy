package no.skatteetaten.aurora.boober.contracts

import no.skatteetaten.aurora.boober.controller.v1.AuroraDeploymentSpecControllerV1
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeployEnvironment
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.Permission
import no.skatteetaten.aurora.boober.model.Permissions
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecService

class AuroradeploymentspecBase extends AbstractContractBase {

  void setup() {
    loadJsonResponses(this)
    def auroraDeploymentSpec = createAuroraDeploymentSpec()
    def auroraDeploymentSpecService = Mock(AuroraDeploymentSpecService) {
      getAuroraDeploymentSpecs(_ as AuroraConfigRef, _ as List) >> [auroraDeploymentSpec]
      getAuroraDeploymentSpec(_ as AuroraConfigRef, _ as String, _ as String) >> auroraDeploymentSpec
      getAuroraDeploymentSpecsForEnvironment(_ as AuroraConfigRef, _ as String) >> [auroraDeploymentSpec]
    }
    AuroraDeploymentSpecControllerV1 controller = new AuroraDeploymentSpecControllerV1(auroraDeploymentSpecService)
    setupMockMvc(controller)
  }

  private AuroraDeploymentSpec createAuroraDeploymentSpec() {
    def deploymentSpecs = response('deploymentspec', '$.items[0]', Map)

    def aid = new ApplicationId("", "")
    def env = new AuroraDeployEnvironment('', '',
        new Permissions(new Permission(Collections.emptySet(), Collections.emptySet()), null), null)
    new AuroraDeploymentSpec(aid, '', TemplateType.development, '', deploymentSpecs,
        '', '', env,
        null, null, null, null, null, null, null, new AuroraConfigFile("", "", false), "master")
  }
}
