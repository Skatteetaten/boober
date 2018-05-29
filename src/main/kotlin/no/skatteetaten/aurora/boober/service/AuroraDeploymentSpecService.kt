package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.mapper.platform.ApplicationPlatformHandler
import no.skatteetaten.aurora.boober.mapper.v1.AuroraBuildMapperV1
import no.skatteetaten.aurora.boober.mapper.v1.AuroraDeployMapperV1
import no.skatteetaten.aurora.boober.mapper.v1.AuroraDeploymentSpecConfigFieldValidator
import no.skatteetaten.aurora.boober.mapper.v1.AuroraDeploymentSpecMapperV1
import no.skatteetaten.aurora.boober.mapper.v1.AuroraIntegrationsMapperV1
import no.skatteetaten.aurora.boober.mapper.v1.AuroraLocalTemplateMapperV1
import no.skatteetaten.aurora.boober.mapper.v1.AuroraRouteMapperV1
import no.skatteetaten.aurora.boober.mapper.v1.AuroraTemplateMapperV1
import no.skatteetaten.aurora.boober.mapper.v1.AuroraVolumeMapperV1
import no.skatteetaten.aurora.boober.mapper.v1.HeaderMapper
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.TemplateType
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import javax.annotation.PostConstruct

@Service
class AuroraDeploymentSpecService(
    val auroraConfigService: AuroraConfigService,
    val aphBeans: List<ApplicationPlatformHandler>
) {

    companion object {
        val logger: Logger = LoggerFactory.getLogger(AuroraDeploymentSpecService::class.java)

        @JvmStatic
        var APPLICATION_PLATFORM_HANDLERS: Map<String, ApplicationPlatformHandler> = emptyMap()

        @JvmOverloads
        @JvmStatic
        fun createAuroraDeploymentSpec(
            auroraConfig: AuroraConfig,
            applicationId: ApplicationId,
            overrideFiles: List<AuroraConfigFile> = listOf()
        ): AuroraDeploymentSpec {

            val applicationFiles = auroraConfig.getFilesForApplication(applicationId, overrideFiles)

            val headerMapper = HeaderMapper(applicationId, applicationFiles)
            val headerFields = AuroraConfigFields.create(headerMapper.handlers, applicationFiles)
            AuroraDeploymentSpecConfigFieldValidator(applicationId, applicationFiles, headerMapper.handlers, headerFields)
                .validate(false)
            val platform = headerFields.extract<String>("applicationPlatform")

            val applicationHandler: ApplicationPlatformHandler = Companion.APPLICATION_PLATFORM_HANDLERS[platform]
                ?: throw IllegalArgumentException("ApplicationPlattformHandler $platform is not present")

            val header = headerMapper.createHeader(headerFields, applicationHandler)

            val deploymentSpecMapper = AuroraDeploymentSpecMapperV1(applicationId)
            val deployMapper = AuroraDeployMapperV1(applicationId, applicationFiles, overrideFiles)
            val integrationMapper = AuroraIntegrationsMapperV1(applicationFiles)
            val volumeMapper = AuroraVolumeMapperV1(applicationFiles)
            val routeMapper = AuroraRouteMapperV1(applicationFiles, header.env, header.name)
            val localTemplateMapper = AuroraLocalTemplateMapperV1(applicationFiles, auroraConfig)
            val templateMapper = AuroraTemplateMapperV1(applicationFiles)
            val buildMapper = AuroraBuildMapperV1(header.name)

            val rawHandlers = (headerMapper.handlers + deploymentSpecMapper.handlers + integrationMapper.handlers + when (header.type) {
                TemplateType.deploy -> deployMapper.handlers + routeMapper.handlers + volumeMapper.handlers
                TemplateType.development -> deployMapper.handlers + routeMapper.handlers + volumeMapper.handlers + buildMapper.handlers
                TemplateType.localTemplate -> routeMapper.handlers + volumeMapper.handlers + localTemplateMapper.handlers
                TemplateType.template -> routeMapper.handlers + volumeMapper.handlers + templateMapper.handlers
                TemplateType.build -> buildMapper.handlers
            }).toSet()

            val handlers = applicationHandler.handlers(rawHandlers)

            val auroraConfigFields = AuroraConfigFields.create(handlers, applicationFiles, header.extractPlaceHolders())

            AuroraDeploymentSpecConfigFieldValidator(applicationId, applicationFiles, handlers, auroraConfigFields).validate()
            val integration = if (header.type == TemplateType.build) null else integrationMapper.integrations(auroraConfigFields)
            val volume = if (header.type == TemplateType.build) null else volumeMapper.createAuroraVolume(auroraConfigFields)
            val route = if (header.type == TemplateType.build) null else routeMapper.route(auroraConfigFields)
            val build = if (header.type == TemplateType.build || header.type == TemplateType.development) buildMapper.build(auroraConfigFields) else null
            val deploy = if (header.type == TemplateType.deploy || header.type == TemplateType.development) deployMapper.deploy(auroraConfigFields) else null
            val template = if (header.type == TemplateType.template) templateMapper.template(auroraConfigFields) else null
            val localTemplate = if (header.type == TemplateType.localTemplate) localTemplateMapper.localTemplate(auroraConfigFields) else null

            return deploymentSpecMapper.createAuroraDeploymentSpec(auroraConfigFields, volume, route, build, deploy, template, integration, localTemplate, header.env, headerMapper.getApplicationFile())
        }
    }

    @PostConstruct
    fun initializeHandlers() {
        APPLICATION_PLATFORM_HANDLERS = aphBeans.associateBy { it.name }
        logger.info("Boober started with applicationPlatformHandlers ${APPLICATION_PLATFORM_HANDLERS.keys}")
    }

    fun getAuroraDeploymentSpecsForEnvironment(auroraConfigName: String, environment: String): List<AuroraDeploymentSpec> {
        val auroraConfig = auroraConfigService.findAuroraConfig(auroraConfigName)
        return auroraConfig.getApplicationIds()
            .filter { it.environment == environment }
            .let { getAuroraDeploymentSpecs(auroraConfig, it) }
    }

    fun getAuroraDeploymentSpecs(auroraConfigName: String, aidStrings: List<String>): List<AuroraDeploymentSpec> {
        val auroraConfig = auroraConfigService.findAuroraConfig(auroraConfigName)
        return aidStrings.map(ApplicationId.Companion::fromString)
            .let { getAuroraDeploymentSpecs(auroraConfig, it) }
    }

    private fun getAuroraDeploymentSpecs(auroraConfig: AuroraConfig, applicationIds: List<ApplicationId>): List<AuroraDeploymentSpec> {
        return applicationIds.map { AuroraDeploymentSpecService.createAuroraDeploymentSpec(auroraConfig, it, listOf()) }
    }

    fun getAuroraDeploymentSpec(auroraConfigName: String, environment: String, application: String): AuroraDeploymentSpec {
        val auroraConfig = auroraConfigService.findAuroraConfig(auroraConfigName)
        return AuroraDeploymentSpecService.createAuroraDeploymentSpec(auroraConfig, ApplicationId.aid(environment, application))
    }
}