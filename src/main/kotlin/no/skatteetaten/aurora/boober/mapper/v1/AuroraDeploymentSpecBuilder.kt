package no.skatteetaten.aurora.boober.mapper.v1

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.mapper.AuroraConfigValidator
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraSecretVault
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.service.DeployBundleService
import org.slf4j.LoggerFactory

val logger = LoggerFactory.getLogger(DeployBundleService::class.java)
@JvmOverloads
fun createAuroraDeploymentSpec(auroraConfig: AuroraConfig, applicationId: ApplicationId, dockerRegistry: String,
                               overrideFiles: List<AuroraConfigFile> = listOf(),
                               vaults: Map<String, AuroraSecretVault> = mapOf()): AuroraDeploymentSpec {

    logger.debug("Get files for application")
    val applicationFiles = auroraConfig.getFilesForApplication(applicationId, overrideFiles)


    logger.debug("Create header mapper")
    val headerMapper = HeaderMapper.create(applicationFiles, applicationId)
    val type = headerMapper.type

    val deploymentSpecMapper = AuroraDeploymentSpecMapperV1(applicationId)
    val deployMapper = AuroraDeployMapperV1(applicationId, applicationFiles, overrideFiles, dockerRegistry)
    val volumeMapper = AuroraVolumeMapperV1(applicationFiles, vaults)
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

    logger.debug("Create aurora config fields")
    val auroraConfigFields = AuroraConfigFields.create(handlers, applicationFiles)
    val validator = AuroraConfigValidator(applicationId, applicationFiles, handlers, auroraConfigFields)
    logger.debug("validate")
    validator.validate()
    logger.debug("/validate")

    val volume = if (type == TemplateType.build) null else volumeMapper.auroraDeploymentCore(auroraConfigFields)
    val route = if (type == TemplateType.build) null else routeMapper.route(auroraConfigFields)

    val build = if (type == TemplateType.build || type == TemplateType.development) buildMapper.build(auroraConfigFields, dockerRegistry) else null

    val deploy = if (type == TemplateType.deploy || type == TemplateType.development) deployMapper.deploy(auroraConfigFields) else null

    val template = if (type == TemplateType.template) templateMapper.template(auroraConfigFields) else null

    val localTemplate = if (type == TemplateType.localTemplate) localTemplateMapper.localTemplate(auroraConfigFields) else null

    logger.debug("create spec")
    return deploymentSpecMapper.createAuroraDeploymentSpec(auroraConfigFields, volume, route, build, deploy, template, localTemplate)
}
