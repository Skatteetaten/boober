package no.skatteetaten.aurora.boober.mapper.v1

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.mapper.AuroraConfigValidator
import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.utils.required

fun createAuroraDeploymentSpec(applicationId: ApplicationId, auroraConfig: AuroraConfig, dockerRegistry: String, overrideFiles: List<AuroraConfigFile>, vaults: Map<String, AuroraSecretVault>): AuroraDeploymentSpec {
    val baseHandlers = setOf(
            AuroraConfigFieldHandler("schemaVersion"),
            AuroraConfigFieldHandler("type", validator = { it.required("Type is required") }),
            AuroraConfigFieldHandler("baseFile"),
            AuroraConfigFieldHandler("envFile")
    )
    val applicationFiles = auroraConfig.getFilesForApplication(applicationId, overrideFiles)
    val fields = AuroraConfigFields.create(baseHandlers, applicationFiles)

    val type = fields.extract("type", { TemplateType.valueOf(it.textValue()) })

    val schemaVersion = fields.extract("schemaVersion")

    if (schemaVersion != "v1") {
        throw IllegalArgumentException("Only v1 of schema is supported")
    }
    val deploymentSpecMapper = AuroraDeploymentSpecMapperV1(applicationId)
    val deployMapper = AuroraDeployMapperV1(applicationId, applicationFiles, overrideFiles, dockerRegistry)
    val volumeMapper = AuroraVolumeMapperV1(applicationFiles, vaults)
    val routeMapper = AuroraRouteMapperV1(applicationFiles)
    val localTemplateMapper = AuroraLocalTemplateMapperV1(applicationFiles, auroraConfig)
    val templateMapper = AuroraTemplateMapperV1(applicationFiles)
    val buildMapper = AuroraBuildMapperV1()
    val handlers = (baseHandlers + deploymentSpecMapper.handlers + when (type) {
        TemplateType.deploy -> routeMapper.handlers + volumeMapper.handlers + deployMapper.handlers
        TemplateType.development -> routeMapper.handlers + volumeMapper.handlers + deployMapper.handlers + buildMapper.handlers
        TemplateType.localTemplate -> routeMapper.handlers + volumeMapper.handlers + localTemplateMapper.handlers
        TemplateType.template -> routeMapper.handlers + volumeMapper.handlers + templateMapper.handlers
        TemplateType.build -> buildMapper.handlers
    }).toSet()

    val auroraConfigFields = AuroraConfigFields.create(handlers, applicationFiles)
    val validator = AuroraConfigValidator(applicationId, applicationFiles, handlers, auroraConfigFields)
    validator.validate()

    val volume = if (type == TemplateType.build) null else volumeMapper.auroraDeploymentCore(auroraConfigFields)
    val route = if (type == TemplateType.build) null else routeMapper.route(auroraConfigFields)

    val build = if (type == TemplateType.build || type == TemplateType.development) buildMapper.build(auroraConfigFields, dockerRegistry) else null

    val deploy = if (type == TemplateType.deploy || type == TemplateType.development) deployMapper.deploy(auroraConfigFields) else null

    val template = if (type == TemplateType.template) templateMapper.template(auroraConfigFields) else null

    val localTemplate = if (type == TemplateType.localTemplate) localTemplateMapper.localTemplate(auroraConfigFields) else null

    return deploymentSpecMapper.createAuroraDeploymentSpec(auroraConfigFields, volume, route, build, deploy, template, localTemplate)
}
