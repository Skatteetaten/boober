package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.feature.extractPlaceHolders
import no.skatteetaten.aurora.boober.mapper.*
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
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

// TODO: Web, toxiproxy, ttl, environment, message, applicationDeployment
interface Feature {

    fun enable(header: AuroraDeploymentContext): Boolean = true
    fun handlers(header: AuroraDeploymentContext): Set<AuroraConfigFieldHandler>
    fun validate(adc: AuroraDeploymentContext, fullValidation: Boolean): List<Exception> = emptyList()
    fun generate(adc: AuroraDeploymentContext): Set<AuroraResource> = emptySet()
    fun modify(adc: AuroraDeploymentContext, resources: Set<AuroraResource>) = Unit

}

typealias FeatureSpec = Map<Feature, AuroraDeploymentContext>

@Service
class AuroraDeploymentSpecService(
        val auroraConfigService: AuroraConfigService,
        val featuers: List<Feature>
) {


    fun createAuroraDeploymentContext(
            auroraConfig: AuroraConfig,
            applicationDeploymentRef: ApplicationDeploymentRef,
            overrideFiles: List<AuroraConfigFile> = listOf(),
            deployId: String = "none"
    ): Pair<AuroraDeploymentContext, Map<Feature, AuroraDeploymentContext>> {

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

        val activeFeatures = featuers.filter { it.enable(headerSpec) }


        val featureHandlers: Map<Feature, Set<AuroraConfigFieldHandler>> = activeFeatures.associateWith {
            it.handlers(headerSpec) + headerMapper.handlers
        }

        val allHandlers: Set<AuroraConfigFieldHandler> = featureHandlers.flatMap { it.value }.toSet()

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
        ).validate()
        val featureAdc: Map<Feature, AuroraDeploymentContext> = featureHandlers.mapValues { (_, handlers) ->
            val paths = handlers.map { it.name }
            val fields = spec.fields.filterKeys {
                paths.contains(it)
            }
            spec.copy(fields = fields)
        }

        /*
        TODO: Fix
        val errors: Map<Feature, Exception> = featureAdc.mapNotNull {
            try {
                it.key.validate(it.value, fullValidation)
                null
            } catch (e: Exception) {
                it.key to e

            }
        }.toMap()

         */

        // TODO: what do we do with errors here?
        return spec to featureAdc
    }

    fun createResources(
            auroraConfig: AuroraConfig,
            applicationDeploymentRef: ApplicationDeploymentRef,
            overrideFiles: List<AuroraConfigFile> = listOf(),
            deployId: String
    ): Set<AuroraResource> {

        // TODO: ADC should contain AuroraDeploymentSpec and featureADC, then we can just generate resoruces whereever.
        val (spec, featureAdc) = createAuroraDeploymentContext(auroraConfig, applicationDeploymentRef, overrideFiles, deployId)




        val featureResources: Set<AuroraResource> = featureAdc.flatMap {
            it.key.generate(it.value)
        }.toSet()

        //Mutation!
        featureAdc.forEach {
            it.key.modify(it.value, featureResources)
        }

        return featureResources
    }

    fun getAuroraDeploymentSpecsForEnvironment(ref: AuroraConfigRef, environment: String): List<AuroraDeploymentContext> {
        val auroraConfig = auroraConfigService.findAuroraConfig(ref)
        return auroraConfig.getApplicationDeploymentRefs()
                .filter { it.environment == environment }
                .let { getAuroraDeploymentSpecs(auroraConfig, it) }
    }

    fun getAuroraDeploymentSpecs(ref: AuroraConfigRef, aidStrings: List<String>): List<AuroraDeploymentContext> {
        val auroraConfig = auroraConfigService.findAuroraConfig(ref)
        return aidStrings.map(ApplicationDeploymentRef.Companion::fromString)
                .let { getAuroraDeploymentSpecs(auroraConfig, it) }
    }

    private fun getAuroraDeploymentSpecs(
            auroraConfig: AuroraConfig,
            applicationDeploymentRefs: List<ApplicationDeploymentRef>
    ): List<AuroraDeploymentContext> {
        return applicationDeploymentRefs.map {
            createAuroraDeploymentContext(
                    auroraConfig = auroraConfig,
                    applicationDeploymentRef = it
            ).first
        }
    }

    fun getAuroraDeploymentContext(
            ref: AuroraConfigRef,
            environment: String,
            application: String,
            overrides: List<AuroraConfigFile> = emptyList()
    ): AuroraDeploymentContext {
        val auroraConfig = auroraConfigService.findAuroraConfig(ref)
        return createAuroraDeploymentContext(
                auroraConfig = auroraConfig,
                overrideFiles = overrides,
                applicationDeploymentRef = ApplicationDeploymentRef.adr(environment, application)
        ).first
    }
}