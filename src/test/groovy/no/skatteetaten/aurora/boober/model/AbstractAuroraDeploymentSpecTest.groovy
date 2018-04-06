package no.skatteetaten.aurora.boober.model

import no.skatteetaten.aurora.boober.mapper.v1.AuroraDeploymentSpecBuilderKt
import no.skatteetaten.aurora.boober.model.AbstractAuroraConfigTest
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec

abstract class AbstractAuroraDeploymentSpecTest extends AbstractAuroraConfigTest {

  static AuroraDeploymentSpec createDeploymentSpec(Map<String, String> auroraConfigJson, ApplicationId applicationId) {

    AuroraConfig auroraConfig = createAuroraConfig(auroraConfigJson)
    def spec = AuroraDeploymentSpecBuilderKt.createAuroraDeploymentSpec(auroraConfig, applicationId)
    spec
  }
}
