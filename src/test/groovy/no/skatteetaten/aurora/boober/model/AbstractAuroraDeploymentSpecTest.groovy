package no.skatteetaten.aurora.boober.model

import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecService

abstract class AbstractAuroraDeploymentSpecTest extends AbstractAuroraConfigTest {

  static AuroraDeploymentSpecInternal createDeploymentSpec(Map<String, String> auroraConfigJson, ApplicationId applicationId) {

    AuroraConfig auroraConfig = createAuroraConfig(auroraConfigJson)
    AuroraDeploymentSpecService.createAuroraDeploymentSpecInternal(auroraConfig, applicationId)
  }
}
