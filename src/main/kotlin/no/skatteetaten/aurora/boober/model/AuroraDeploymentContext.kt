package no.skatteetaten.aurora.boober.model

import mu.KotlinLogging
import no.skatteetaten.aurora.boober.feature.Feature
import no.skatteetaten.aurora.boober.service.ContextCreationErrors
import no.skatteetaten.aurora.boober.service.ContextErrors
import no.skatteetaten.aurora.boober.service.ExceptionList
import no.skatteetaten.aurora.boober.service.MultiApplicationDeployValidationResultException
import no.skatteetaten.aurora.boober.utils.UUIDGenerator
import no.skatteetaten.aurora.boober.utils.parallelMap

private val logger = KotlinLogging.logger { }

fun AuroraDeploymentContext.validate(fullValidation: Boolean): Map<Feature, List<java.lang.Exception>> {
    return features.mapValues {
        val ctx = featureContext[it.key] ?: emptyMap()
        try {
            it.key.validate(it.value, fullValidation, ctx)
        } catch (e: Exception) {
            if (e is ExceptionList) {
                e.exceptions
            } else {
                listOf(e)
            }
        }
    }
}

fun List<AuroraDeploymentContext>.createDeployCommand(
    deploy: Boolean
): List<AuroraDeployCommand> {
    val errorsOrFeatures = this.createResources()
        .flattenErrorsOrFeatures()

    val result: List<Pair<List<ContextErrors>, AuroraDeployCommand?>> = errorsOrFeatures.parallelMap {
        val context = it.ctx
        val (errors, resourceResults) = it.errorsOrFeatures
        when {
            errors.isNotEmpty() -> errors to null
            resourceResults == null -> listOf(
                ContextCreationErrors(
                    context.cmd,
                    listOf(RuntimeException("No resources generated"))
                )
            ) to null
            else -> {

                val (header, normal) = resourceResults.partition { it.header }
                emptyList<ContextErrors>() to AuroraDeployCommand(
                    headerResources = header.toSet(),
                    resources = normal.toSet(),
                    context = context,
                    deployId = UUIDGenerator.deployId,
                    shouldDeploy = deploy
                )
            }
        }
    }

    val pairedResults = result.partition {
        it.second != null
    }
    val valid = pairedResults.first.mapNotNull { it.second }
    val invalid = pairedResults.second.map { it.first }.flatten()

    if (invalid.isNotEmpty()) {
        val errorMessages = invalid.map { err ->
            err.errors.map { it.localizedMessage }
        }
        logger.debug("Validation errors: ${errorMessages.joinToString("\n", prefix = "\n")}")
        throw MultiApplicationDeployValidationResultException(
            valid = valid,
            invalid = invalid,
            errorMessage = "Invalid deploy commands"
        )
    }

    return valid
}

fun List<AuroraDeploymentContext>.createResources():
        Map<AuroraDeploymentContext, List<Pair<ContextErrors?, Set<AuroraResource>?>>> {

    val generatedSequential = this.associateWith { context -> context.generateResources(true) }
    val generatedParallel = this.parallelMap { context -> context to context.generateResources(false) }.toMap()

    return this.associateWith { context ->
        val l1 = generatedSequential[context].orEmpty()
        val l2 = generatedParallel[context].orEmpty()
        l1 + l2
    }
}

fun AuroraDeploymentContext.generateResources(isRunSequential: Boolean): List<Pair<ContextErrors?, Set<AuroraResource>?>> =
    this.features.map { (feature, adc) ->
        val context = this.featureContext[feature] ?: emptyMap()
        try {
            if (isRunSequential) {
                null to feature.generateSequentially(adc, context)
            } else {
                null to feature.generate(adc, context)
            }
        } catch (e: Throwable) {
            if (e is ExceptionList) {
                ContextCreationErrors(this.cmd, e.exceptions) to null
            } else {
                ContextCreationErrors(this.cmd, listOf(e)) to null
            }
        }
    }.filter {
        it.first != null || !it.second.isNullOrEmpty()
    }

fun Map<AuroraDeploymentContext, List<Pair<ContextErrors?, Set<AuroraResource>?>>>.flattenErrorsOrFeatures():
        List<ErrorsOrFeatures> {
    return this.map { (context, eitherErrorsOrFeatures) ->
        // There was some errors when generating so we gather them up and return them and no resources
        val errors = eitherErrorsOrFeatures.mapNotNull { it.first }
        if (errors.isNotEmpty()) {
            return@map ErrorsOrFeatures(context, Pair(errors, null))
        }

        val featureResources = eitherErrorsOrFeatures.mapNotNull { it.second }.flatten().toSet()
        val names = featureResources.map { "${it.resource.kind}/${it.resource.metadata.name}" }

        val uniqueNames = names.toSet()

        val duplicatedNames = uniqueNames.filter { uniqueName ->
            names.count { uniqueName == it } != 1
        }

        // TODO: This failed Test this
        if (duplicatedNames.isNotEmpty()) {
            val namesString = duplicatedNames.joinToString(", ").lowercase()
            val error: List<ContextErrors> = listOf(
                ContextCreationErrors(
                    context.cmd,
                    listOf(RuntimeException("The following resources are generated more then once $namesString"))
                )
            )
            return@map ErrorsOrFeatures(context, Pair(error, featureResources))
        }

        // TODO: Message:     Resource with duplicate kind/name=Service/activemq-3-native,Service/activemq-3-admin,Route/activemq-3,ImageStream/amq-classic-single,DeploymentConfig/activemq-3,Secret/activemq-3-cert,Route/activemq-3,ApplicationDeployment/activemq-3,ProjectRequest/paas-m89870,Namespace/paas-m89870,RoleBinding/admin

        // Mutation!
        val modifyErrors = context.features.mapNotNull {
            val featureContext = context.featureContext[it.key] ?: emptyMap()
            try {
                it.key.modify(it.value, featureResources, featureContext)
                null
            } catch (e: Throwable) {
                if (e is ExceptionList) {
                    ContextCreationErrors(context.cmd, e.exceptions)
                } else {
                    ContextCreationErrors(context.cmd, listOf(e))
                }
            }
        }

        return@map ErrorsOrFeatures(context, Pair(modifyErrors, featureResources))
    }
}

typealias FeatureSpec = Map<Feature, AuroraDeploymentSpec>

data class AuroraDeploymentContext(
    val spec: AuroraDeploymentSpec,
    val cmd: AuroraContextCommand,
    val features: FeatureSpec,
    val featureContext: Map<Feature, Map<String, Any>>,
    val warnings: List<String> = emptyList()
)

data class InvalidDeploymentContext(
    val cmd: AuroraContextCommand,
    val errors: ContextErrors
)

data class ErrorsOrFeatures(
    val ctx: AuroraDeploymentContext,
    val errorsOrFeatures: Pair<List<ContextErrors>, Set<AuroraResource>?>
)
