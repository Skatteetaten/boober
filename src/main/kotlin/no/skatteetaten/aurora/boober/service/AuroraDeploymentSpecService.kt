package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.feature.extractPlaceHolders
import no.skatteetaten.aurora.boober.mapper.AuroraConfigException
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentContext
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.mapper.platform.ApplicationPlatformHandler
import no.skatteetaten.aurora.boober.mapper.v1.*
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpecInternal
import no.skatteetaten.aurora.boober.model.AuroraRoute
import no.skatteetaten.aurora.boober.model.ConfigFieldErrorDetail
import no.skatteetaten.aurora.boober.model.TemplateType
import org.apache.commons.text.StringSubstitutor
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import javax.annotation.PostConstruct

data class AuroraResource(
        val name: String,
        val resource: HasMetadata
)

fun Set<AuroraResource>.addEnvVar(envVars: List<EnvVar>) {
    this.forEach {
        if (it.resource.kind == "DeploymentConfig") {
            val dc: DeploymentConfig = jacksonObjectMapper().convertValue(it.resource)
            dc.spec.template.spec.containers.forEach { container ->
                container.env.addAll(envVars)
            }
        }
    }
}

interface Feature {

    fun enable(header: AuroraDeploymentContext): Boolean = true
    fun handlers(header: AuroraDeploymentContext): Set<AuroraConfigFieldHandler>
    fun validate(adc: AuroraDeploymentContext) = Unit
    fun generate(adc: AuroraDeploymentContext): Set<AuroraResource> = emptySet()
    fun modify(adc: AuroraDeploymentContext, resources: Set<AuroraResource>) = Unit

}

@Service
class AuroraDeploymentSpecService(
        val auroraConfigService: AuroraConfigService,
        val aphBeans: List<ApplicationPlatformHandler>,
        val featuers: List<Feature>
) {

    fun createResources(
            auroraConfig: AuroraConfig,
            applicationDeploymentRef: ApplicationDeploymentRef,
            overrideFiles: List<AuroraConfigFile> = listOf(),
            deployId: String
    ): Set<AuroraResource> {

        val applicationFiles: List<AuroraConfigFile> = auroraConfig.getFilesForApplication(applicationDeploymentRef, overrideFiles)

        val headerMapper = HeaderMapper(applicationDeploymentRef, applicationFiles)
        val headerSpec =
                AuroraDeploymentContext.create(
                        handlers = headerMapper.handlers,
                        files = applicationFiles,
                        applicationDeploymentRef = applicationDeploymentRef,
                        auroraConfig = auroraConfig,
                        deployId = deployId
                )

        AuroraDeploymentSpecConfigFieldValidator(
                applicationDeploymentRef = applicationDeploymentRef,
                applicationFiles = applicationFiles,
                fieldHandlers = headerMapper.handlers,
                fields = headerSpec.fields
        ).validate(false)
        /*

//Hvordan skal vi håndetere ressurser som allerede finnes i clusteret og merging av dem?
//Skal vi hente dem først og ha dem med i algoritmen, eller skal vi merge dem etterpå slik som nå?
//Undersøke: Spillet resrouceVersion av owner reference noe
//hvilke features har vi

- parse en template fra fil
- parse en template fra en ressurs i clusteret
- DBH
- STS cert
- Config
- Mounts
- AuroraVault
- AuroraLabel: Legger på alle standard labels
- AuroraDeployment: Lager AuroraDeployment og setter owner reference på alle andre i modify
- Build
- DeploymentConfig
- Route
- BigIP
- Webseal
*/

        val activeFeatures = featuers.filter { it.enable(headerSpec) }


        val featureHandlers: Map<Feature, Set<AuroraConfigFieldHandler>> = activeFeatures.associateWith {
            it.handlers(headerSpec)
        }

        //The order here really matters unfortunately. Version is handled differently
        val allHandlers: Set<AuroraConfigFieldHandler> = (featureHandlers.flatMap { it.value } + headerMapper.handlers).toSet()

        val spec = AuroraDeploymentContext.create(
                handlers = allHandlers,
                files = applicationFiles,
                applicationDeploymentRef = applicationDeploymentRef,
                auroraConfig = auroraConfig,
                replacer = StringSubstitutor(headerSpec.extractPlaceHolders(), "@", "@"),
                deployId = deployId
        )
        AuroraDeploymentSpecConfigFieldValidator(
                applicationDeploymentRef = applicationDeploymentRef,
                applicationFiles = applicationFiles,
                fieldHandlers = allHandlers,
                fields = spec.fields
        ).validate(false)

        val featureAdc: Map<Feature, AuroraDeploymentContext> = featureHandlers.mapValues { (_, handlers) ->
            val paths = handlers.map { it.name } + headerMapper.handlers.map { it.name }
            val fields = spec.fields.filterKeys {
                paths.contains(it)
            }
            spec.copy(fields = fields)
        }


        //deep validation
        /*
        val errors: Map<Feature, Exception> = featureAdc.mapNotNull {
            try {
                it.key.validate(it.value)
                null
            } catch (e:Exception){
                it.key to e

            }
        }.toMap()
         */

        val featureResources: Set<AuroraResource> = featureAdc.flatMap {
            it.key.generate(it.value)
        }.toSet()

        //Mutation!
        featureAdc.forEach {
            it.key.modify(it.value, featureResources)
        }

        return featureResources
        /*
        return featureAdc
                .toList()
                .fold(featureResources) { resources, (feature, adc): Pair<Feature, AuroraDeploymentSpec> ->
                    feature.modify(adc, resources)
                }
         */
    }


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
                    applicationFiles = applicationFiles,
                    fieldHandlers = headerMapper.handlers,
                    fields = headerSpec.fields
            ).validate(false)

            val platform: String = headerSpec["applicationPlatform"]

            val applicationHandler: ApplicationPlatformHandler = APPLICATION_PLATFORM_HANDLERS[platform]
                    ?: throw IllegalArgumentException("ApplicationPlatformHandler $platform is not present")

            val header = headerMapper.createHeader(headerSpec, applicationHandler)

            val replacer = StringSubstitutor(header.extractPlaceHolders(), "@", "@")
            val deploymentSpecMapper = AuroraDeploymentSpecMapperV1(applicationDeploymentRef, applicationFiles)
            val deployMapper = AuroraDeployMapperV1(applicationDeploymentRef, applicationFiles)
            val integrationMapper = AuroraIntegrationsMapperV1(applicationFiles, header.name, header.env.affiliation)
            val volumeMapper = AuroraVolumeMapperV1(applicationFiles, header.name, replacer)
            val routeMapper = AuroraRouteMapperV1(applicationFiles, header.name, replacer)
            val localTemplateMapper = AuroraLocalTemplateMapperV1(applicationFiles, auroraConfig)
            val templateMapper = AuroraTemplateMapperV1(applicationFiles)
            val buildMapper = AuroraBuildMapperV1(header.name)

            val typeHandlers = when (header.type) {
                TemplateType.deploy -> deployMapper.handlers
                TemplateType.development -> deployMapper.handlers + buildMapper.handlers
                TemplateType.localTemplate -> localTemplateMapper.handlers
                TemplateType.template -> templateMapper.handlers
            }

            val handlers =
                    headerMapper.handlers +
                            deploymentSpecMapper.handlers +
                            typeHandlers +
                            applicationHandler.handlers(header.type) +
                            integrationMapper.handlers +
                            routeMapper.handlers +
                            volumeMapper.handlers

            val deploymentSpec = AuroraDeploymentSpec.create(
                    handlers = handlers.toSet(),
                    files = applicationFiles,
                    applicationDeploymentRef = applicationDeploymentRef,
                    configVersion = auroraConfig.version,
                    replacer = replacer
            )

            AuroraDeploymentSpecConfigFieldValidator(
                    applicationDeploymentRef = applicationDeploymentRef,
                    applicationFiles = applicationFiles,
                    fieldHandlers = handlers,
                    fields = deploymentSpec.fields
            ).validate()

            val integration = integrationMapper.integrations(deploymentSpec)
            val volume = volumeMapper.createAuroraVolume(deploymentSpec)
            val route = routeMapper.route(deploymentSpec)

            validateRoutes(route, applicationDeploymentRef)

            val build = if (header.type == TemplateType.development) buildMapper.build(deploymentSpec) else null
            val deploy =
                    if (header.type == TemplateType.deploy || header.type == TemplateType.development) deployMapper.deploy(
                            deploymentSpec
                    ) else null
            val template =
                    if (header.type == TemplateType.template) templateMapper.template(deploymentSpec) else null
            val localTemplate =
                    if (header.type == TemplateType.localTemplate) localTemplateMapper.localTemplate(deploymentSpec) else null

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
                    configVersion = auroraConfig.version,
                    files = applicationFiles
            )
        }

        @JvmStatic
        fun validateRoutes(
                auroraRoute: AuroraRoute,
                applicationDeploymentRef: ApplicationDeploymentRef
        ) {

            auroraRoute.route.forEach {
                if (it.tls != null && it.host.contains('.')) {
                    throw AuroraConfigException(
                            "Application ${applicationDeploymentRef.application} in environment ${applicationDeploymentRef.environment} have a tls enabled route with a '.' in the host",
                            errors = listOf(ConfigFieldErrorDetail.illegal(message = "Route name=${it.objectName} with tls uses '.' in host name"))
                    )
                }
            }

            val routeNames = auroraRoute.route.groupBy { it.objectName }
            val duplicateRoutes = routeNames.filter { it.value.size > 1 }.map { it.key }

            if (duplicateRoutes.isNotEmpty()) {
                throw AuroraConfigException(
                        "Application ${applicationDeploymentRef.application} in environment ${applicationDeploymentRef.environment} have routes with duplicate names",
                        errors = duplicateRoutes.map {
                            ConfigFieldErrorDetail.illegal(message = "Route name=$it is duplicated")
                        }
                )
            }

            val duplicatedHosts = auroraRoute.route.groupBy { it.target }.filter { it.value.size > 1 }
            if (duplicatedHosts.isNotEmpty()) {
                throw AuroraConfigException(
                        "Application ${applicationDeploymentRef.application} in environment ${applicationDeploymentRef.environment} have duplicated targets",
                        errors = duplicatedHosts.map { route ->
                            val routes = route.value.joinToString(",") { it.objectName }
                            ConfigFieldErrorDetail.illegal(message = "target=${route.key} is duplicated in routes $routes")
                        }
                )
            }
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
            createAuroraDeploymentSpec(
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
        return createAuroraDeploymentSpec(
                auroraConfig = auroraConfig,
                overrideFiles = overrides,
                applicationDeploymentRef = ApplicationDeploymentRef.adr(environment, application)
        )
    }
}