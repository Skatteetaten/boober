package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.mapper.AuroraConfigMapper
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfig
import no.skatteetaten.aurora.boober.service.internal.*
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import org.springframework.stereotype.Service

@Service
class AuroraConfigValidationService(val openShiftClient: OpenShiftClient) {

    fun validate(auroraConfig: AuroraConfig) {
        val appIds = auroraConfig.getApplicationIds()
        // Verify that all AuroraDeploymentConfigs represented by the AuroraConfig are valid
        createAuroraDcs(auroraConfig, appIds)
    }

    @JvmOverloads
    fun createAuroraDcs(auroraConfig: AuroraConfig,
                        applicationIds: List<ApplicationId>,
                        overrides: List<AuroraConfigFile> = listOf()): List<AuroraDeploymentConfig> {

        return applicationIds.map { aid ->
            try {
                val adc = createAuroraDc(aid, auroraConfig, overrides)
                Result<AuroraDeploymentConfig, Error?>(value = adc)
            } catch (e: ApplicationConfigException) {
                Result<AuroraDeploymentConfig, Error?>(error = Error(aid, e.errors))
            } catch (e: IllegalArgumentException) {
                Result<AuroraDeploymentConfig, Error?>(error = Error(aid, listOf(ValidationError(e.message!!))))
            }
        }.orElseThrow {
            AuroraConfigException("AuroraConfig contained errors for one or more applications", it)
        }
    }

    @JvmOverloads
    fun createAuroraDc(aid: ApplicationId, auroraConfig: AuroraConfig, overrides: List<AuroraConfigFile> = emptyList()): AuroraDeploymentConfig {
        val allFiles: List<AuroraConfigFile> = auroraConfig.getFilesForApplication(aid, overrides)
        val mapper = AuroraConfigMapper.createMapper(aid, auroraConfig, allFiles, openShiftClient)
        mapper.validate()
        val adc = mapper.toAuroraDeploymentConfig()
        return adc
    }
}
