package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.openshift.api.model.DeploymentConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.slf4j.MDCContext
import no.skatteetaten.aurora.boober.controller.security.SpringSecurityThreadContextElement
import no.skatteetaten.aurora.boober.feature.extractPlaceHolders
import no.skatteetaten.aurora.boober.feature.name
import no.skatteetaten.aurora.boober.feature.namespace
import no.skatteetaten.aurora.boober.mapper.*
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.ApplicationRef
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import org.apache.commons.text.StringSubstitutor
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.util.StopWatch

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
        val featuers: List<Feature>,
        @Value("\${boober.validationPoolSize:6}") val validationPoolSize: Int
) {

    val logger: Logger = LoggerFactory.getLogger(AuroraDeploymentSpecService::class.java)
    private val dispatcher = newFixedThreadPoolContext(validationPoolSize, "validationPool")

    fun expandDeploymentRefToApplicationRef(
            auroraConfig: AuroraConfig,
            adr: List<ApplicationDeploymentRef>
    ): List<ApplicationRef> = createValidatedAuroraDeploymentContexts(
            AuroraConfigWithOverrides(auroraConfig),
            adr).map {
        ApplicationRef(it.namespace, it.name)
    }

    fun createValidatedAuroraDeploymentContexts(
            auroraConfigWithOverrides: AuroraConfigWithOverrides,
            applicationDeploymentRefs: List<ApplicationDeploymentRef>,
            resourceValidation: Boolean = true
    ): List<AuroraDeploymentContext> {

        val stopWatch = StopWatch().apply { start() }
        val specInternals: List<AuroraDeploymentContext> = runBlocking(
                MDCContext() + SpringSecurityThreadContextElement()
        ) {
            applicationDeploymentRefs.map { aid ->
                async(dispatcher) {
                    try {
                        val spec = createAuroraDeploymentContext(
                                auroraConfig = auroraConfigWithOverrides.auroraConfig,
                                applicationDeploymentRef = aid,
                                overrideFiles = auroraConfigWithOverrides.overrideFiles
                        ).first
                        Pair<AuroraDeploymentContext?, ExceptionWrapper?>(first = spec, second = null)
                    } catch (e: Throwable) {
                        Pair<AuroraDeploymentContext?, ExceptionWrapper?>(
                                first = null,
                                second = ExceptionWrapper(aid, e)
                        )
                    }
                }
            }
                    .map { it.await() }
        }.onErrorThrow(::MultiApplicationValidationException)
        stopWatch.stop()
        logger.debug("Validated AuroraConfig ${auroraConfigWithOverrides.auroraConfig.name} with ${applicationDeploymentRefs.size} applications in ${stopWatch.totalTimeMillis} millis")
        return specInternals
    }

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

    fun getAuroraDeploymentSpecs(
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
}