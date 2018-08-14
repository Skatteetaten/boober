package no.skatteetaten.aurora.boober.contracts

/*
TODO : må gå gjennom hvordan dette gjøres
import no.skatteetaten.aurora.boober.controller.v1.AuroraDeploymentSpecControllerV1
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeployEnvironment
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpecInternal
import no.skatteetaten.aurora.boober.model.Permission
import no.skatteetaten.aurora.boober.model.Permissions
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecService
class AuroradeploymentspecBase extends AbstractContractBase {

  void setup() {
    loadJsonResponses(this)
    def auroraDeploymentSpecInternal = createAuroraDeploymentSpecInternal()
    def auroraDeploymentSpecService = Mock(AuroraDeploymentSpecService) {
      getAuroraDeploymentSpecs(_ as AuroraConfigRef, _ as List) >> [auroraDeploymentSpecInternal]
      getAuroraDeploymentSpecInternal(_ as AuroraConfigRef, _ as String, _ as String, _ as List) >> auroraDeploymentSpecInternal
      getAuroraDeploymentSpecsForEnvironment(_ as AuroraConfigRef, _ as String) >> [auroraDeploymentSpecInternal]
    }
    AuroraDeploymentSpecControllerV1 controller = new AuroraDeploymentSpecControllerV1(auroraDeploymentSpecService)
    setupMockMvc(controller)
  }

  private AuroraDeploymentSpecInternal createAuroraDeploymentSpecInternal() {
    def deploymentSpecs = response('deploymentspec', '$.items[0]', Map)
    // TODO: Dette må konverteres til Map<String, AuroraConfigField>

    def aid = new ApplicationId("", "")
    def env = new AuroraDeployEnvironment('', '',
        new Permissions(new Permission(Collections.emptySet(), Collections.emptySet()), null), null)

    new AuroraDeploymentSpecInternal(aid, '', TemplateType.development, '', deploymentSpecs,
        '', '', env,
        null, null, null, null, null, null, null, new AuroraConfigFile("", "", false), "master", [:])
  }
}
*/
