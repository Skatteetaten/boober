package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.feature.Feature
import no.skatteetaten.aurora.boober.feature.extractPlaceHolders
import no.skatteetaten.aurora.boober.feature.headerHandlers
import no.skatteetaten.aurora.boober.feature.name
import no.skatteetaten.aurora.boober.feature.namespace
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.ApplicationRef
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentContext
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.validate
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import org.apache.commons.text.StringSubstitutor
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class AuroraDeploymentContextService(
    val featuers: List<Feature>
) {

    val logger: Logger = LoggerFactory.getLogger(AuroraDeploymentContextService::class.java)

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
            logger.debug(errors.joinToString(","))
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
