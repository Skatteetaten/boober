package no.skatteetaten.aurora.boober.mapper.v1

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.mapper.AuroraConfigValidator
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.TemplateType

@JvmOverloads
fun createAuroraDeploymentSpec(auroraConfig: AuroraConfig, applicationId: ApplicationId,
                               overrideFiles: List<AuroraConfigFile> = listOf()): AuroraDeploymentSpec {

    val applicationFiles = auroraConfig.getFilesForApplication(applicationId, overrideFiles)

    val headerMapper = HeaderMapper.create(applicationFiles, applicationId)
    val type = headerMapper.type

    val deploymentSpecMapper = AuroraDeploymentSpecMapperV1(applicationId)
    val deployMapper = AuroraDeployMapperV1(applicationId, applicationFiles, overrideFiles)
    val volumeMapper = AuroraVolumeMapperV1(applicationFiles)
    val routeMapper = AuroraRouteMapperV1(applicationId, applicationFiles)
    val localTemplateMapper = AuroraLocalTemplateMapperV1(applicationFiles, auroraConfig)
    val templateMapper = AuroraTemplateMapperV1(applicationFiles)
    val buildMapper = AuroraBuildMapperV1(applicationId)

    val handlers = (HeaderMapper.handlers + deploymentSpecMapper.handlers + when (type) {
        TemplateType.deploy -> deployMapper.handlers + routeMapper.handlers + volumeMapper.handlers
        TemplateType.development -> deployMapper.handlers + routeMapper.handlers + volumeMapper.handlers + buildMapper.handlers
        TemplateType.localTemplate -> routeMapper.handlers + volumeMapper.handlers + localTemplateMapper.handlers
        TemplateType.template -> routeMapper.handlers + volumeMapper.handlers + templateMapper.handlers
        TemplateType.build -> buildMapper.handlers
    }).toSet()

    val auroraConfigFields = AuroraConfigFields.create(handlers, applicationFiles)
    val validator = AuroraConfigValidator(applicationId, applicationFiles, handlers, auroraConfigFields)
    validator.validate()

    val volume = if (type == TemplateType.build) null else volumeMapper.createAuroraVolume(auroraConfigFields)
    val route = if (type == TemplateType.build) null else routeMapper.route(auroraConfigFields)

    val build = if (type == TemplateType.build || type == TemplateType.development) buildMapper.build(auroraConfigFields) else null

    val deploy = if (type == TemplateType.deploy || type == TemplateType.development) deployMapper.deploy(auroraConfigFields) else null

    val template = if (type == TemplateType.template) templateMapper.template(auroraConfigFields) else null

    val localTemplate = if (type == TemplateType.localTemplate) localTemplateMapper.localTemplate(auroraConfigFields) else null

    return deploymentSpecMapper.createAuroraDeploymentSpec(auroraConfigFields, volume, route, build, deploy, template, localTemplate)
}
