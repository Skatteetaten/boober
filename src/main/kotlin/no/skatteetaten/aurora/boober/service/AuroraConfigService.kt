package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.facade.DeployBundle
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.mapper.AuroraConfigValidator
import no.skatteetaten.aurora.boober.mapper.v1.AuroraApplicationMapperV1
import no.skatteetaten.aurora.boober.mapper.v1.AuroraBuildMapperV1
import no.skatteetaten.aurora.boober.mapper.v1.AuroraDeployMapperV1
import no.skatteetaten.aurora.boober.mapper.v1.AuroraLocalTemplateMapperV1
import no.skatteetaten.aurora.boober.mapper.v1.AuroraRouteMapperV1
import no.skatteetaten.aurora.boober.mapper.v1.AuroraTemplateMapperV1
import no.skatteetaten.aurora.boober.mapper.v1.AuroraVolumeMapperV1
import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.model.TemplateType.build
import no.skatteetaten.aurora.boober.model.TemplateType.deploy
import no.skatteetaten.aurora.boober.model.TemplateType.development
import no.skatteetaten.aurora.boober.model.TemplateType.localTemplate
import no.skatteetaten.aurora.boober.model.TemplateType.template
import no.skatteetaten.aurora.boober.service.internal.ApplicationConfigException
import no.skatteetaten.aurora.boober.service.internal.AuroraConfigException
import no.skatteetaten.aurora.boober.service.internal.Error
import no.skatteetaten.aurora.boober.service.internal.Result
import no.skatteetaten.aurora.boober.service.internal.ValidationError
import no.skatteetaten.aurora.boober.service.internal.onErrorThrow
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.utils.required
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class AuroraConfigService(val openShiftClient: OpenShiftClient,
                          @Value("\${boober.docker.registry}") val dockerRegistry: String) {
    val logger: Logger = LoggerFactory.getLogger(AuroraConfigService::class.java)


    fun validate(deployBundle: DeployBundle) {

        tryCreateAuroraApplications(deployBundle, deployBundle.auroraConfig.getApplicationIds())
    }


    fun createAuroraApplications(deployBundle: DeployBundle, applicationIds: List<ApplicationId>): List<AuroraApplication> {

        return tryCreateAuroraApplications(deployBundle, applicationIds)
    }


    fun createAuroraApplication(deployBundle: DeployBundle, applicationId: ApplicationId): AuroraApplication {

        val baseHandlers = setOf(
                AuroraConfigFieldHandler("schemaVersion"),
                AuroraConfigFieldHandler("type", validator = { it.required("Type is required") }),
                AuroraConfigFieldHandler("baseFile"),
                AuroraConfigFieldHandler("envFile")
        )
        val auroraConfig = deployBundle.auroraConfig
        val overrideFiles = deployBundle.overrideFiles
        val vaults = deployBundle.vaults
        val applicationFiles = auroraConfig.getFilesForApplication(applicationId, overrideFiles)
        val fields = AuroraConfigFields.create(baseHandlers, applicationFiles)

        val type = fields.extract("type", { TemplateType.valueOf(it.textValue()) })

        val schemaVersion = fields.extract("schemaVersion")

        if (schemaVersion != "v1") {
            throw IllegalArgumentException("Only v1 of schema is supported")
        }
        val applicationMapper = AuroraApplicationMapperV1(openShiftClient, applicationId)
        val deployMapper = AuroraDeployMapperV1(applicationId, applicationFiles, deployBundle.overrideFiles, dockerRegistry)
        val volumeMapper = AuroraVolumeMapperV1(applicationFiles, vaults)
        val routeMapper = AuroraRouteMapperV1(applicationFiles)
        val localTemplateMapper = AuroraLocalTemplateMapperV1(applicationFiles, auroraConfig)
        val templateMapper = AuroraTemplateMapperV1(applicationFiles, openShiftClient)
        val buildMapper = AuroraBuildMapperV1()
        val handlers = (baseHandlers + applicationMapper.handlers + when (type) {
            deploy -> routeMapper.handlers + volumeMapper.handlers + deployMapper.handlers
            development -> routeMapper.handlers + volumeMapper.handlers + deployMapper.handlers + buildMapper.handlers
            localTemplate -> routeMapper.handlers + volumeMapper.handlers + localTemplateMapper.handlers
            template -> routeMapper.handlers + volumeMapper.handlers + templateMapper.handlers
            build -> buildMapper.handlers
        }).toSet()

        val auroraConfigFields = AuroraConfigFields.create(handlers, applicationFiles)
        val validator = AuroraConfigValidator(applicationId, applicationFiles, handlers, auroraConfigFields)
        validator.validate()


        val volume = if (type == build) null else volumeMapper.auroraDeploymentCore(auroraConfigFields)
        val route = if (type == build) null else routeMapper.route(auroraConfigFields)

        val build = if (type == build || type == development) buildMapper.build(auroraConfigFields, dockerRegistry) else null

        val deploy = if (type == deploy || type == development) deployMapper.deploy(auroraConfigFields) else null

        val template = if (type == template) templateMapper.template(auroraConfigFields) else null

        val localTemplate = if (type == localTemplate) localTemplateMapper.localTemplate(auroraConfigFields) else null

        return applicationMapper.auroraApplicationConfig(auroraConfigFields, volume, route, build, deploy, template, localTemplate)
    }


    private fun tryCreateAuroraApplications(deployBundle: DeployBundle, applicationIds: List<ApplicationId>): List<AuroraApplication> {

        return applicationIds.map { aid ->
            try {
                val value = createAuroraApplication(deployBundle, aid)
                Result<AuroraApplication, Error?>(value = value)
            } catch (e: ApplicationConfigException) {
                logger.debug("ACE {}", e.errors)
                Result<AuroraApplication, Error?>(error = Error(aid.application, aid.environment, e.errors))
            } catch (e: IllegalArgumentException) {
                logger.debug("IAE {}", e.message)
                Result<AuroraApplication, Error?>(error = Error(aid.application, aid.environment, listOf(ValidationError(e.message!!))))
            }
        }.onErrorThrow {
            logger.debug("ACE {}", it)
            AuroraConfigException("AuroraConfig contained errors for one or more applications", it)
        }
    }

}
