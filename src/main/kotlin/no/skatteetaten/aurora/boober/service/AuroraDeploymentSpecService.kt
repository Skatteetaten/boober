package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.Volume
import io.fabric8.kubernetes.api.model.VolumeMount
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
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import org.apache.commons.text.StringSubstitutor
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.util.StopWatch

data class AuroraResource(
        val name: String,
        val resource: HasMetadata,
        val header: Boolean = false //these resources are only created once for each deploy){}
)

fun Set<AuroraResource>.addEnvVar(envVars: List<EnvVar>) {
    this.filter { it.resource.kind == "DeploymentConfig" }.forEach {
        val dc: DeploymentConfig = jacksonObjectMapper().convertValue(it.resource)
        dc.allNonSideCarContainers.forEach { container ->
            container.env.addAll(envVars)
        }
    }
}

fun Set<AuroraResource>.addVolumesAndMounts(envVars: List<EnvVar> = emptyList(), volumes: List<Volume> = emptyList(), volumeMounts: List<VolumeMount> = emptyList()) {
    this.filter { it.resource.kind == "DeploymentConfig" }.forEach {
        val dc: DeploymentConfig = jacksonObjectMapper().convertValue(it.resource)
        dc.spec.template.spec.volumes = dc.spec.template.spec.volumes.addIfNotNull(volumes)
        dc.allNonSideCarContainers.forEach { container ->
            container.volumeMounts = container.volumeMounts.addIfNotNull(volumeMounts)
            container.env = container.env.addIfNotNull(envVars)
        }
    }
}

// TODO:  environment
interface Feature {

    fun enable(header: AuroraDeploymentSpec): Boolean = true
    fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler>
    fun validate(adc: AuroraDeploymentSpec, fullValidation: Boolean, cmd: AuroraContextCommand): List<Exception> = emptyList()
    fun generate(adc: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraResource> = emptySet()
    fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, cmd: AuroraContextCommand) = Unit

}


@Service
class AuroraDeploymentSpecService(
        val featuers: List<Feature>,
        @Value("\${boober.validationPoolSize:6}") val validationPoolSize: Int
) {

    val logger: Logger = LoggerFactory.getLogger(AuroraDeploymentSpecService::class.java)
    private val dispatcher = newFixedThreadPoolContext(validationPoolSize, "validationPool")

    fun expandDeploymentRefToApplicationRef(
            auroraConfig: AuroraConfig,
            adr: List<ApplicationDeploymentRef>,
            ref: AuroraConfigRef
    ): List<ApplicationRef> {
        return getAuroraDeploymentContexts(auroraConfig, adr, ref).map {
            ApplicationRef(it.spec.namespace, it.spec.name)
        }
    }

    fun createValidatedAuroraDeploymentContexts(
            commands: List<AuroraContextCommand>,
            resourceValidation: Boolean = true
    ): List<AuroraDeploymentContext> {

        val stopWatch = StopWatch().apply { start() }
        val specInternals: List<AuroraDeploymentContext> = runBlocking(
                MDCContext() + SpringSecurityThreadContextElement()
        ) {
            commands.map { cmd ->
                async(dispatcher) {
                    try {
                        //TODO: Handle error here and make this code cleaner
                        val context = createAuroraDeploymentContext(cmd)
                        context.validate(resourceValidation)

                        Pair<AuroraDeploymentContext?, ExceptionWrapper?>(first = context, second = null)
                    } catch (e: Throwable) {
                        Pair<AuroraDeploymentContext?, ExceptionWrapper?>(
                                first = null,
                                second = ExceptionWrapper(cmd.applicationDeploymentRef, e)
                        )
                    }
                }
            }
                    .map { it.await() }
        }.onErrorThrow(::MultiApplicationValidationException)
        stopWatch.stop()
        val name = commands.first().auroraConfig.name
        logger.debug("Validated AuroraConfig ${name} with ${commands.size} applications in ${stopWatch.totalTimeMillis} millis")
        return specInternals
    }

    fun createAuroraDeploymentContext(
            deployCommand: AuroraContextCommand
    ): AuroraDeploymentContext {

        val headerMapper = HeaderMapper(deployCommand.applicationDeploymentRef, deployCommand.applicationFiles)
        val headerSpec =
                AuroraDeploymentSpec.create(
                        handlers = headerMapper.handlers,
                        files = deployCommand.applicationFiles,
                        applicationDeploymentRef = deployCommand.applicationDeploymentRef,
                        auroraConfigVersion = deployCommand.auroraConfig.version
                )

        AuroraDeploymentSpecConfigFieldValidator(
                applicationDeploymentRef = deployCommand.applicationDeploymentRef,
                applicationFiles = deployCommand.applicationFiles,
                fieldHandlers = headerMapper.handlers,
                fields = headerSpec.fields
        ).validate(false)

        val activeFeatures = featuers.filter { it.enable(headerSpec) }


        val featureHandlers: Map<Feature, Set<AuroraConfigFieldHandler>> = activeFeatures.associateWith {
            it.handlers(headerSpec, deployCommand) + headerMapper.handlers
        }

        val allHandlers: Set<AuroraConfigFieldHandler> = featureHandlers.flatMap { it.value }.toSet()

        val spec = AuroraDeploymentSpec.create(
                handlers = allHandlers,
                files = deployCommand.applicationFiles,
                applicationDeploymentRef = deployCommand.applicationDeploymentRef,
                auroraConfigVersion = deployCommand.auroraConfig.version,
                replacer = StringSubstitutor(headerSpec.extractPlaceHolders(), "@", "@")
        )
        AuroraDeploymentSpecConfigFieldValidator(
                applicationDeploymentRef = deployCommand.applicationDeploymentRef,
                applicationFiles = deployCommand.applicationFiles,
                fieldHandlers = allHandlers,
                fields = spec.fields
        ).validate()
        val featureAdc: Map<Feature, AuroraDeploymentSpec> = featureHandlers.mapValues { (_, handlers) ->
            val paths = handlers.map { it.name }
            val fields = spec.fields.filterKeys {
                paths.contains(it)
            }
            spec.copy(fields = fields)
        }


        return AuroraDeploymentContext(spec, cmd = deployCommand, features = featureAdc)
    }


    fun getAuroraDeploymentContexts(
            auroraConfig: AuroraConfig,
            applicationDeploymentRefs: List<ApplicationDeploymentRef>,
            ref: AuroraConfigRef
    ): List<AuroraDeploymentContext> {
        return applicationDeploymentRefs.map {
            createAuroraDeploymentContext(AuroraContextCommand(auroraConfig, it, ref))
        }
    }
}