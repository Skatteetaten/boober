package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.mapper.v1.createAuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import org.springframework.stereotype.Service

@Service
class AuroraDeploymentSpecService(val auroraConfigService: AuroraConfigService) {

    fun getAuroraDeploymentSpecsForEnvironment(auroraConfigName: String, environment: String): List<AuroraDeploymentSpec> {
        val auroraConfig = auroraConfigService.findAuroraConfig(auroraConfigName)
        return auroraConfig.getApplicationIds()
                .filter { it.environment == environment }
                .let { getAuroraDeploymentSpecsForApplicationIds(auroraConfig, it) }
    }

    fun getAuroraDeploymentSpecs(auroraConfigName: String, aidStrings: List<String>): List<AuroraDeploymentSpec> {
        val auroraConfig = auroraConfigService.findAuroraConfig(auroraConfigName)
        return aidStrings.map(ApplicationId.Companion::fromString)
                .let { getAuroraDeploymentSpecsForApplicationIds(auroraConfig, it) }
    }

    fun getAuroraDeploymentSpec(auroraConfigName: String, environment: String, application: String): AuroraDeploymentSpec {
        val auroraConfig = auroraConfigService.findAuroraConfig(auroraConfigName)
        return createAuroraDeploymentSpec(auroraConfig, ApplicationId.aid(environment, application))
    }

    private fun getAuroraDeploymentSpecsForApplicationIds(auroraConfig: AuroraConfig, applicationIds: List<ApplicationId>): List<AuroraDeploymentSpec> {
        return if (applicationIds.isEmpty()) {
            auroraConfig.getAllAuroraDeploymentSpecs()
        } else {
            applicationIds.map { auroraConfig.getAuroraDeploymentSpec(it) }
        }
    }

}