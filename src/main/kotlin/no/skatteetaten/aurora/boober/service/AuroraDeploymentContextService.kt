package no.skatteetaten.aurora.boober.service

import org.apache.commons.text.StringSubstitutor
import org.springframework.stereotype.Service
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.feature.AbstractResolveTagFeature
import no.skatteetaten.aurora.boober.feature.AlertsFeature
import no.skatteetaten.aurora.boober.feature.BigIpFeature
import no.skatteetaten.aurora.boober.feature.CertificateFeature
import no.skatteetaten.aurora.boober.feature.ConfigFeature
import no.skatteetaten.aurora.boober.feature.Feature
import no.skatteetaten.aurora.boober.feature.FeatureContext
import no.skatteetaten.aurora.boober.feature.HeaderHandlers
import no.skatteetaten.aurora.boober.feature.RouteFeature
import no.skatteetaten.aurora.boober.feature.StsFeature
import no.skatteetaten.aurora.boober.feature.WebsealFeature
import no.skatteetaten.aurora.boober.feature.affiliation
import no.skatteetaten.aurora.boober.feature.azure.AzureFeature
import no.skatteetaten.aurora.boober.feature.cluster
import no.skatteetaten.aurora.boober.feature.envName
import no.skatteetaten.aurora.boober.feature.extractPlaceHolders
import no.skatteetaten.aurora.boober.feature.name
import no.skatteetaten.aurora.boober.feature.namespace
import no.skatteetaten.aurora.boober.model.ApplicationRef
import no.skatteetaten.aurora.boober.model.AuroraConfigException
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentContext
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.ErrorType
import no.skatteetaten.aurora.boober.model.InvalidDeploymentContext
import no.skatteetaten.aurora.boober.model.validate
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.parallelMap
import no.skatteetaten.aurora.boober.utils.takeIfNotEmpty
import no.skatteetaten.aurora.boober.utils.toMultiMap

private val logger = KotlinLogging.logger {}

typealias UrlToApplicationDeploymentContextMultimap = Map<String, List<AuroraDeploymentContext>>

typealias UrlAndAuroraDeploymentContextList = List<Pair<String, AuroraDeploymentContext>>

typealias FeatureToAdcMap = Map<Feature, AuroraDeploymentSpec>

@Service
class AuroraDeploymentContextService(
    val features: List<Feature>,
    val idService: IdService?,
    val idServiceFallback: IdServiceFallback?
) {
    @Suppress("UNCHECKED_CAST")
    fun createValidatedAuroraDeploymentContexts(
        commands: List<AuroraContextCommand>,
        resourceValidation: Boolean = true
    ): Pair<List<AuroraDeploymentContext>, List<InvalidDeploymentContext>> = commands.parallelMap { cmd ->
        logger.debug("Create ADC for app=${cmd.applicationDeploymentRef}")

        runCatching {
            val context = createAuroraDeploymentContext(cmd, resourceValidation)

            context.validate(resourceValidation).flatMap {
                it.value
            }.takeIfNotEmpty()?.let {
                InvalidDeploymentContext(cmd, ContextResourceValidationErrors(cmd, it, context))
            } ?: context
        }.getOrElse {
            InvalidDeploymentContext(cmd, ContextCreationErrors(cmd, listOf(it)))
        }
    }
        .partition { it is AuroraDeploymentContext }
        .let { it as Pair<List<AuroraDeploymentContext>, List<InvalidDeploymentContext>> }
        .let { it.first.addDuplicatedUrls() to it.second }

    private fun List<AuroraDeploymentContext>.addDuplicatedUrls(): List<AuroraDeploymentContext> {
        val adcWithDuplicatedUrls = findDuplicatedUrlWarningsGroupedByAuroraDeploymentContext()

        return map {
            when {
                adcWithDuplicatedUrls.containsKey(it) -> it.copy(
                    warnings = it.warnings.addIfNotNull(adcWithDuplicatedUrls[it])
                )
                else -> it
            }
        }
    }

    private fun List<AuroraDeploymentContext>.findDuplicatedUrlWarningsGroupedByAuroraDeploymentContext() =
        findContextExternalUrlPairs().findDuplicatedUrlsWithADC().createContextWarningMap()

    // create a warning for each host/context combination and group them by context
    private fun UrlToApplicationDeploymentContextMultimap.createContextWarningMap() = flatMap { (host, contexts) ->
        val adrString = contexts.map { it.cmd.applicationDeploymentRef.toString() }.distinct()
        val warningString =
            "The external url=$host is not uniquely defined. " +
                "Only the last applied configuration will be valid. " +
                "The following configurations references it=$adrString"
        contexts.map {
            it to warningString
        }
    }.toMultiMap()

    // find all the externalHosts both configured as annotations and on bigip feature
    private fun List<AuroraDeploymentContext>.findContextExternalUrlPairs() = flatMap { adc ->
        adc.features.flatMap { (feature, spec) ->
            when (feature) {
                is RouteFeature -> feature.fetchExternalHostsAndPaths(spec)
                is BigIpFeature -> feature.fetchExternalHostsAndPaths(spec)
                else -> emptyList()
            }
        }.map { it to adc }
    }

    // group them by externalHost+path and filter out any instance that is there more then once. Note that one ADR can be in this list several times if it has configured more routes or bigip annotation that conflicts
    private fun UrlAndAuroraDeploymentContextList.findDuplicatedUrlsWithADC() = toMultiMap().filter {
        it.value.size > 1
    }

    fun findApplicationRef(deployCommand: AuroraContextCommand): ApplicationRef {
        val adc = createAuroraDeploymentContext(deployCommand, false)

        return ApplicationRef(adc.spec.namespace, adc.spec.name)
    }

    // Do not want to expose createADC without it beeing validated
    fun findApplicationDeploymentSpec(deployCommand: AuroraContextCommand) =
        createAuroraDeploymentContext(deployCommand, false).spec

    private fun createAuroraDeploymentContext(
        deployCommand: AuroraContextCommand,
        fullValidation: Boolean
    ): AuroraDeploymentContext {

        val headerHandlers = deployCommand.applicationDeploymentRef
            .run { HeaderHandlers.create(application, environment) }.handlers
        val headerSpec =
            AuroraDeploymentSpec.createHeader(
                handlers = headerHandlers,
                files = deployCommand.applicationFiles,
                applicationDeploymentRef = deployCommand.applicationDeploymentRef,
                auroraConfigVersion = deployCommand.auroraConfig.ref,
            )

        val headerErrors = AuroraDeploymentSpecConfigFieldValidator(
            applicationFiles = deployCommand.applicationFiles,
            fieldHandlers = headerHandlers,
            fields = headerSpec.fields
        ).validate(false)

        val filteredHeaderErrors = headerErrors.filter { it.type != ErrorType.WARNING }
        // throws exception for header errors that are not warnings
        if (!deployCommand.errorsAsWarnings && filteredHeaderErrors.isNotEmpty()) {
            throw AuroraConfigException(
                "Config for application ${deployCommand.applicationDeploymentRef.application} in environment ${deployCommand.applicationDeploymentRef.environment} contains errors",
                errors = filteredHeaderErrors
            )
        }

        val activeFeatures = features.filter { it.enable(headerSpec) }

        val featureHandlers: Map<Feature, Set<AuroraConfigFieldHandler>> = activeFeatures.associateWith {
            it.handlers(headerSpec, deployCommand) + headerHandlers
        }

        val allHandlers: Set<AuroraConfigFieldHandler> =
            featureHandlers.flatMap { it.value }.toSet().addIfNotNull(headerHandlers)

        val applicationDeploymentId = headerSpec.let {
            idService?.generateOrFetchId(
                ApplicationDeploymentCreateRequest(
                    name = it.name,
                    environmentName = it.envName,
                    cluster = it.cluster,
                    businessGroup = it.affiliation
                )
            ) ?: idServiceFallback?.generateOrFetchId(it.name, it.namespace)
                ?: throw RuntimeException("Unable to generate applicationDeploymentId, no idService available")
        }

        val spec = AuroraDeploymentSpec.createComplete(
            applicationDeploymentId = applicationDeploymentId,
            handlers = allHandlers,
            files = deployCommand.applicationFiles,
            applicationDeploymentRef = deployCommand.applicationDeploymentRef,
            auroraConfigVersion = deployCommand.auroraConfig.ref,
            replacer = StringSubstitutor(headerSpec.extractPlaceHolders(), "@", "@"),
            namespace = headerSpec.namespace,
            affiliation = headerSpec.affiliation
        )

        val errors = AuroraDeploymentSpecConfigFieldValidator(
            applicationFiles = deployCommand.applicationFiles,
            fieldHandlers = allHandlers,
            fields = spec.fields
        ).validate()

        val filteredErrors = errors.filter { it.type != ErrorType.WARNING }
        // throws exception for  errors that are not warnings
        if (!deployCommand.errorsAsWarnings && filteredErrors.isNotEmpty()) {
            throw AuroraConfigException(
                "Config for application ${deployCommand.applicationDeploymentRef.application} in environment ${deployCommand.applicationDeploymentRef.environment} contains errors",
                errors = filteredErrors
            )
        }

        val featureAdc: FeatureToAdcMap = featureHandlers.mapValues { (_, handlers) ->
            val paths = handlers.map { it.name } + listOf("applicationDeploymentId", "namespace")
            val fields = spec.fields.filterKeys {
                paths.contains(it)
            }
            spec.copy(fields = fields)
        }

        val featureContext = featureAdc.mapValues { (feature, featureSpec) ->
            feature.createContext(featureSpec, deployCommand, !fullValidation)
        }

        val errorWarnings = if (deployCommand.errorsAsWarnings) {
            (headerErrors + errors).map {
                it.asWarning()
            }.distinct()
        } else {
            (headerErrors + errors).filter { it.type == ErrorType.WARNING }.map { it.asWarning() }.distinct()
        }

        return AuroraDeploymentContext(
            spec,
            cmd = deployCommand,
            features = featureAdc,
            featureContext = featureContext,
            warnings = findWarnings(deployCommand, featureAdc, featureContext, fullValidation) + errorWarnings
        )
    }

    private fun findWarnings(
        cmd: AuroraContextCommand,
        features: FeatureToAdcMap,
        context: Map<Feature, FeatureContext>,
        fullValidation: Boolean
    ): List<String> {

        val logWarning = { warning: String ->
            val auroraConfigRef = cmd.auroraConfigRef
            logger.debug(
                "AuroraConfigWarning auroraConfig=${auroraConfigRef.name} " +
                    "auroraConfigGitReference=${auroraConfigRef.refName} " +
                    "deploymentReference=${cmd.applicationDeploymentRef} warning=$warning"
            )
        }

        return features.findConfigKeyWithSpecialChar(context, fullValidation, logWarning) +
            features.gatherDeprecationWarnings() +
            features.websealWarnings(cmd, logWarning) +
            features.stsWarnings(logWarning)
    }

    private fun FeatureToAdcMap.gatherDeprecationWarnings() = this.mapNotNull { (feature, spec) ->
        if (feature is AzureFeature && feature.isActive(spec)) {
            feature.getDeprecations(spec)
        } else if (feature is AlertsFeature && feature.containsDeprecatedConnection(spec)) {
            listOf("The property 'connection' on alerts is deprecated. Please use the connections property")
        } else {
            null
        }
    }.flatten()

    private fun FeatureToAdcMap.websealWarnings(cmd: AuroraContextCommand, logWarning: (String) -> Unit): List<String> {

        val webseal = this.any { (feature, spec) ->
            feature is WebsealFeature && feature.willCreateResource(spec) && feature.shouldWarnAboutFeature(spec)
        }

        val directRoute = this.any { (feature, spec) ->
            feature is RouteFeature && feature.willCreateResource(spec, cmd)
        }

        val azureRoute = this.any { (feature, spec) ->
            feature is AzureFeature && feature.isActive(spec)
        }

        return listOfNotNull(
            if (webseal && directRoute) {
                logWarning("websealAndRoute")
                "Both Webseal-route and OpenShift-Route generated for application. " +
                    "If your application relies on WebSeal security this can be harmful! " +
                    "Set webseal/strict to false to remove this warning."
            } else null,
            if (webseal && azureRoute) {
                logWarning("websealAndAzureRoute")
                "Both Webseal-route and Azure-Route generated for application. " +
                    "If your application relies on WebSeal security this can be harmful! " +
                    "Set webseal/strict to false to remove this warning."
            } else null
        )
    }

    private fun FeatureToAdcMap.stsWarnings(logWarning: (String) -> Unit): List<String> {
        val certificate = this.any { (feature, spec) ->
            feature is CertificateFeature && feature.willCreateResource(spec)
        }

        val sts = this.any { (feature, spec) ->
            feature is StsFeature && feature.willCreateResource(spec)
        }

        return listOfNotNull(
            if (sts && certificate) {
                logWarning("stsAndCertificate")
                "Both sts and certificate feature has generated a cert. " +
                    "Turn off certificate if you are using the new STS service"
            } else null
        )
    }

    private fun FeatureToAdcMap.findConfigKeyWithSpecialChar(
        context: Map<Feature, FeatureContext>,
        fullValidation: Boolean,
        logWarning: (String) -> Unit,
    ) = this.flatMap { (feature, spec) ->
        val featureContext = context[feature]
            ?: throw RuntimeException("Could not fetch context for feature=${feature::class.qualifiedName}")
        when (feature) {
            is ConfigFeature -> {
                feature.envVarsKeysWithSpecialCharacters(spec).map {
                    logWarning("configKeyWithSpecialChar")
                    it
                }
            }
            is AbstractResolveTagFeature -> {
                if (feature.isActive(spec) && fullValidation) {
                    val warning = feature.dockerDigestExistsWarning(featureContext)
                    logWarning("dockerDigestDoesNotExist:${feature::class.simpleName}")
                    listOfNotNull(
                        warning
                    )
                } else emptyList()
            }
            else -> emptyList()
        }
    }
}
