package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.mapper.AuroraConfigMapper
import no.skatteetaten.aurora.boober.mapper.AuroraConfigMapper.Companion.baseHandlers
import no.skatteetaten.aurora.boober.mapper.v1.AuroraConfigMapperV1Deploy
import no.skatteetaten.aurora.boober.mapper.v1.AuroraConfigMapperV1LocalTemplate
import no.skatteetaten.aurora.boober.mapper.v1.AuroraConfigMapperV1Template
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfig
import no.skatteetaten.aurora.boober.model.DeployCommand
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.service.internal.*
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import org.springframework.stereotype.Service

@Service
class AuroraConfigService(val openShiftClient: OpenShiftClient) {


    fun validate(auroraConfig: AuroraConfig) {
        val appIds = auroraConfig.getApplicationIds()
        appIds.map { aid ->
            try {
                val mapper = createMapper(aid, auroraConfig)
                mapper.validate()
                Result<Boolean, Error?>(value = true)
            } catch (e: ApplicationConfigException) {
                Result<AuroraDeploymentConfig, Error?>(error = Error(aid, e.errors))
            } catch (e: IllegalArgumentException) {
                Result<AuroraDeploymentConfig, Error?>(error = Error(aid, listOf(ValidationError(e.message!!))))
            }
        }.orElseThrow {
            AuroraConfigException("AuroraConfig contained errors for one or more applications", it)
        }
    }

    fun createAuroraDcs(auroraConfig: AuroraConfig, applicationIds: List<DeployCommand>): List<AuroraDeploymentConfig> {

        return applicationIds.map { aid ->
            try {
                val adc = createAuroraDc(aid, auroraConfig)
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

    fun createAuroraDc(aid: DeployCommand, auroraConfig: AuroraConfig): AuroraDeploymentConfig {
        val mapper = createMapper(aid, auroraConfig)
        mapper.validate()
        val adc = mapper.toAuroraDeploymentConfig()
        return adc
    }


    fun createMapper(aid: DeployCommand, auroraConfig: AuroraConfig): AuroraConfigMapper {

        val fields = AuroraConfigFields.create(baseHandlers, auroraConfig.getFilesForApplication(aid))

        val type = fields.extract("type", { TemplateType.valueOf(it.textValue()) })

        val schemaVersion = fields.extract("schemaVersion")

        if (schemaVersion != "v1") {
            throw IllegalArgumentException("Only v1 of schema is supported")
        }

        if (type == TemplateType.localTemplate) {
            return AuroraConfigMapperV1LocalTemplate(aid, auroraConfig, openShiftClient)
        }

        if (type == TemplateType.template) {
            return AuroraConfigMapperV1Template(aid, auroraConfig, openShiftClient)

        }

        return AuroraConfigMapperV1Deploy(aid, auroraConfig, openShiftClient)
    }

}
