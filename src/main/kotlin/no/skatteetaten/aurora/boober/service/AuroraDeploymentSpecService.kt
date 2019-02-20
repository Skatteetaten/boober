package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
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
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpecInternal
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
            applicationDeploymentRef: ApplicationDeploymentRef,
            overrideFiles: List<AuroraConfigFile> = listOf()
        ): AuroraDeploymentSpec {
            return createAuroraDeploymentSpecInternal(
                auroraConfig,
                applicationDeploymentRef,
                overrideFiles
            ).spec
        }

        @JvmOverloads
        @JvmStatic
        fun createAuroraDeploymentSpecInternal(
            auroraConfig: AuroraConfig,
            applicationDeploymentRef: ApplicationDeploymentRef,
            overrideFiles: List<AuroraConfigFile> = listOf()
        ): AuroraDeploymentSpecInternal {

            val applicationFiles = auroraConfig.getFilesForApplication(applicationDeploymentRef, overrideFiles)

            val headerMapper = HeaderMapper(applicationDeploymentRef, applicationFiles)
            val headerSpec =
                AuroraDeploymentSpec.create(
                    headerMapper.handlers,
                    applicationFiles,
                    applicationDeploymentRef,
                    auroraConfig.version
                )

            AuroraDeploymentSpecConfigFieldValidator(
                applicationDeploymentRef = applicationDeploymentRef,
                applicationFiles =
                applicationFiles,
                fieldHandlers = headerMapper.handlers,
                auroraDeploymentSpec = headerSpec
            )
                .validate(false)
            val platform: String = headerSpec["applicationPlatform"]

            val applicationHandler: ApplicationPlatformHandler = APPLICATION_PLATFORM_HANDLERS[platform]
                ?: throw IllegalArgumentException("ApplicationPlatformHandler $platform is not present")

            val header = headerMapper.createHeader(headerSpec, applicationHandler)

            val deploymentSpecMapper = AuroraDeploymentSpecMapperV1(applicationDeploymentRef, applicationFiles)
            val deployMapper = AuroraDeployMapperV1(applicationDeploymentRef, applicationFiles)
            val integrationMapper = AuroraIntegrationsMapperV1(applicationFiles, header.name, header.env.affiliation)
            val volumeMapper = AuroraVolumeMapperV1(applicationFiles)
            val routeMapper = AuroraRouteMapperV1(applicationFiles, header.name)
            val localTemplateMapper = AuroraLocalTemplateMapperV1(applicationFiles, auroraConfig)
            val templateMapper = AuroraTemplateMapperV1(applicationFiles)
            val buildMapper = AuroraBuildMapperV1(header.name)

            val rawHandlers =
                (headerMapper.handlers + deploymentSpecMapper.handlers + integrationMapper.handlers + when (header.type) {
                    TemplateType.deploy -> deployMapper.handlers + routeMapper.handlers + volumeMapper.handlers
                    TemplateType.development -> deployMapper.handlers + routeMapper.handlers + volumeMapper.handlers + buildMapper.handlers
                    TemplateType.localTemplate -> routeMapper.handlers + volumeMapper.handlers + localTemplateMapper.handlers
                    TemplateType.template -> routeMapper.handlers + volumeMapper.handlers + templateMapper.handlers
                }).toSet()

            val handlers = applicationHandler.handlers(rawHandlers)

            val deploymentSpec = AuroraDeploymentSpec.create(
                handlers = handlers,
                files = applicationFiles,
                applicationDeploymentRef = applicationDeploymentRef,
                configVersion = auroraConfig.version,
                placeholders = header.extractPlaceHolders()
            )

            AuroraDeploymentSpecConfigFieldValidator(

                applicationDeploymentRef = applicationDeploymentRef,
                applicationFiles = applicationFiles,
                fieldHandlers = handlers,
                auroraDeploymentSpec = deploymentSpec
            ).validate()

            val integration = integrationMapper.integrations(deploymentSpec)
            val volume = volumeMapper.createAuroraVolume(deploymentSpec)
            val route = routeMapper.route(deploymentSpec)
            val build =
                if (header.type == TemplateType.development) buildMapper.build(deploymentSpec) else null
            val deploy =
                if (header.type == TemplateType.deploy || header.type == TemplateType.development) deployMapper.deploy(
                    deploymentSpec
                ) else null
            val template =
                if (header.type == TemplateType.template) templateMapper.template(deploymentSpec) else null
            val localTemplate =
                if (header.type == TemplateType.localTemplate) localTemplateMapper.localTemplate(deploymentSpec) else null

            val overrides = overrideFiles.associate { it.name to it.contents }

            return deploymentSpecMapper.createAuroraDeploymentSpec(
                auroraDeploymentSpec = deploymentSpec,
                volume = volume,
                route = route,
                build = build,
                deploy = deploy,
                template = template,
                integration = integration,
                localTemplate = localTemplate,
                env = header.env,
                applicationFile = headerMapper.getApplicationFile(),
                configVersion = auroraConfig.version,
                overrideFiles = overrides
            )
        }
    }

    @PostConstruct
    fun initializeHandlers() {
        APPLICATION_PLATFORM_HANDLERS = aphBeans.associateBy { it.name }
        logger.info("Boober started with applicationPlatformHandlers ${APPLICATION_PLATFORM_HANDLERS.keys}")
    }

    fun getAuroraDeploymentSpecsForEnvironment(ref: AuroraConfigRef, environment: String): List<AuroraDeploymentSpec> {
        val auroraConfig = auroraConfigService.findAuroraConfig(ref)
        return auroraConfig.getApplicationDeploymentRefs()
            .filter { it.environment == environment }
            .let { getAuroraDeploymentSpecs(auroraConfig, it) }
    }

    fun getAuroraDeploymentSpecs(ref: AuroraConfigRef, aidStrings: List<String>): List<AuroraDeploymentSpec> {
        val auroraConfig = auroraConfigService.findAuroraConfig(ref)
        return aidStrings.map(ApplicationDeploymentRef.Companion::fromString)
            .let { getAuroraDeploymentSpecs(auroraConfig, it) }
    }

    private fun getAuroraDeploymentSpecs(
        auroraConfig: AuroraConfig,
        applicationDeploymentRefs: List<ApplicationDeploymentRef>
    ): List<AuroraDeploymentSpec> {
        return applicationDeploymentRefs.map {
            AuroraDeploymentSpecService.createAuroraDeploymentSpec(
                auroraConfig,
                it,
                listOf()
            )
        }
    }

    fun getAuroraDeploymentSpec(
        ref: AuroraConfigRef,
        environment: String,
        application: String,
        overrides: List<AuroraConfigFile> = emptyList()
    ): AuroraDeploymentSpec {
        val auroraConfig = auroraConfigService.findAuroraConfig(ref)
        return AuroraDeploymentSpecService.createAuroraDeploymentSpec(
            auroraConfig = auroraConfig,
            overrideFiles = overrides,
            applicationDeploymentRef = ApplicationDeploymentRef.aid(environment, application)
        )
    }
}