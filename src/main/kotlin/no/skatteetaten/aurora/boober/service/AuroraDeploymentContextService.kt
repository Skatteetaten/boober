package no.skatteetaten.aurora.boober.service

import mu.KotlinLogging
import no.skatteetaten.aurora.boober.feature.CertificateFeature
import no.skatteetaten.aurora.boober.feature.Feature
import no.skatteetaten.aurora.boober.feature.RouteFeature
import no.skatteetaten.aurora.boober.feature.StsFeature
import no.skatteetaten.aurora.boober.feature.WebsealFeature
import no.skatteetaten.aurora.boober.feature.extractPlaceHolders
import no.skatteetaten.aurora.boober.feature.headerHandlers
import no.skatteetaten.aurora.boober.feature.name
import no.skatteetaten.aurora.boober.feature.namespace
import no.skatteetaten.aurora.boober.model.ApplicationRef
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentContext
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.validate
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.parallelMap
import org.apache.commons.text.StringSubstitutor
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

@Service
class AuroraDeploymentContextService(
    val features: List<Feature>
) {

    fun createValidatedAuroraDeploymentContexts(
        commands: List<AuroraContextCommand>,
        resourceValidation: Boolean = true
    ): List<AuroraDeploymentContext> {

        val result: List<Pair<AuroraDeploymentContext?, ContextErrors?>> = commands.parallelMap { cmd ->
            try {
                logger.debug("Create ADC for app=${cmd.applicationDeploymentRef}")
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
            logger.debug("Validation errors: ${errorMessages.joinToString("\n", prefix = "\n")}")
            throw MultiApplicationValidationException(errors)
        }
        return result.mapNotNull { it.first }
    }

    fun findApplicationRef(deployCommand: AuroraContextCommand): ApplicationRef {

        val adc = createAuroraDeploymentContext(deployCommand)
        return ApplicationRef(adc.spec.namespace, adc.spec.name)
    }

    // Do not want to expose createADC without it beeing validated
    fun findApplicationDeploymentSpec(deployCommand: AuroraContextCommand) =
        createAuroraDeploymentContext(deployCommand).spec

    private fun createAuroraDeploymentContext(
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

        val activeFeatures = features.filter { it.enable(headerSpec) }

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
            featureHandlers = featureHandlers,
            warnings = findWarnings(deployCommand, featureAdc)
        )
    }

    private fun findWarnings(cmd: AuroraContextCommand, features: Map<Feature, AuroraDeploymentSpec>): List<String> {

        fun logWarning(warning: String) {
            val auroraConfigRef = cmd.auroraConfigRef
            logger.info("AuroraConfigWarning auroraConfig=${auroraConfigRef.name} auroraConfigGitReference=${auroraConfigRef.refName} deploymentReference=${cmd.applicationDeploymentRef} warning=$warning")
        }

        val webSeal = features.filter { (feature, spec) ->
            feature is WebsealFeature && feature.willCreateResource(spec) && feature.shouldWarnAboutFeature(spec)
        }.isNotEmpty()

        val route = features.filter { (feature, spec) ->
            feature is RouteFeature && feature.willCreateResource(spec, cmd)
        }.isNotEmpty()

        val websealWarning = if (webSeal && route) {
            logWarning("websealAndRoute")
            "Both Webseal-route and OpenShift-Route generated for application. If your application relies on WebSeal security this can be harmful! Set webseal/strict to false to remove this error."
        } else null

        val sts = features.filter { (feature, spec) ->
            feature is StsFeature && feature.willCreateResource(spec)
        }.isNotEmpty()

        val certificate = features.filter { (feature, spec) ->
            feature is CertificateFeature && feature.willCreateResource(spec)
        }.isNotEmpty()

        val stsWarning = if (sts && certificate) {
            logWarning("stsAndCertificate")
            "Both sts and certificate feature has generated a cert. Turn off certificate if you are using the new STS service"
        } else null

        return listOfNotNull(websealWarning, stsWarning)
    }
}
