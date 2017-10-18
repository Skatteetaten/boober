package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import no.skatteetaten.aurora.boober.mapper.v1.AuroraDeploymentSpecBuilderKt
import no.skatteetaten.aurora.boober.model.AbstractAuroraConfigTest
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import spock.lang.Specification

import static no.skatteetaten.aurora.boober.model.ApplicationId.aid


abstract class AbstractAuroraDeploymentSpecTest extends AbstractAuroraConfigTest {

   static AuroraDeploymentSpec createDeploymentSpec(Map<String, String> auroraConfigJson, ApplicationId applicationId) {

        AuroraConfig auroraConfig = createAuroraConfig(auroraConfigJson)
        AuroraDeploymentSpecBuilderKt.createAuroraDeploymentSpec(auroraConfig, applicationId, "", [], [:])
    }
}
