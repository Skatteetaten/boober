package no.skatteetaten.aurora.boober.service

import mu.KotlinLogging
import no.skatteetaten.aurora.boober.feature.*
import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import org.apache.commons.text.StringSubstitutor
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

@Service
class AuroraDeploymentContextService(
    val featuers: List<Feature>
) {

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

        val result: List<Pair<AuroraDeploymentContext?, ContextErrors?>> = commands.map { cmd ->
            try {
                val context = createAuroraDeploymentContext(cmd)

                val errors = context.validate(resourceValidation).flatMap { it.value }

                if (errors.isEmpty()) {
                    context to null
                } else {
                    context to ContextErrors(cmd, errors)
                }
            } catch (e: Throwable) {
                null to ContextErrors(cmd, listOf(e))
            }
        }

        val errors = result.mapNotNull { it.second }
        if (errors.isNotEmpty()) {
            val errorMessages = errors.flatMap { err ->
                err.errors.map { it.localizedMessage }
            }
            logger.debug(errorMessages.joinToString("\n", prefix = "\n"))
            throw MultiApplicationValidationException(errors)
        }
        return result.mapNotNull { it.first }
    }

    fun createAuroraDeploymentContext(
        deployCommand: AuroraContextCommand
    ): AuroraDeploymentContext {

        val headerHandlers = deployCommand.applicationDeploymentRef.headerHandlers
        val headerSpec =
            AuroraDeploymentSpec.create(
                handlers = headerHandlers,
                files = deployCommand.applicationFiles,
                applicationDeploymentRef = deployCommand.applicationDeploymentRef,
                auroraConfigVersion = deployCommand.auroraConfig.version
            )

        AuroraDeploymentSpecConfigFieldValidator(
            applicationDeploymentRef = deployCommand.applicationDeploymentRef,
            applicationFiles = deployCommand.applicationFiles,
            fieldHandlers = headerHandlers,
            fields = headerSpec.fields
        ).validate(false)

        val activeFeatures = featuers.filter { it.enable(headerSpec) }

        val featureHandlers: Map<Feature, Set<AuroraConfigFieldHandler>> = activeFeatures.associateWith {
            it.handlers(headerSpec, deployCommand) + headerHandlers
        }

        val allHandlers: Set<AuroraConfigFieldHandler> =
            featureHandlers.flatMap { it.value }.toSet().addIfNotNull(headerHandlers)

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

        return AuroraDeploymentContext(
            spec,
            cmd = deployCommand,
            features = featureAdc,
            featureHandlers = featureHandlers
        )
    }

    fun getAuroraDeploymentContexts(
        auroraConfig: AuroraConfig,
        applicationDeploymentRefs: List<ApplicationDeploymentRef>,
        ref: AuroraConfigRef
    ): List<AuroraDeploymentContext> {
        return applicationDeploymentRefs.map {
            createAuroraDeploymentContext(
                AuroraContextCommand(
                    auroraConfig,
                    it,
                    ref
                )
            )
        }
    }
}
