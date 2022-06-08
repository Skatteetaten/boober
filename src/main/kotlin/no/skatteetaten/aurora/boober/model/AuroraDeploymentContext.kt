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
    val errorsOrFeatures = this.mergeGeneratedResources()
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

fun List<AuroraDeploymentContext>.mergeGeneratedResources():
    Map<AuroraDeploymentContext, List<Pair<ContextErrors?, Set<AuroraResource>?>>> {

    val generatedSequential = this.associateWith { context -> context.generateResources(RunType.SEQUENTIAL) }
    val generatedParallel = this.parallelMap { context -> context to context.generateResources(RunType.PARALLEL) }.toMap()

    return this.associateWith { context ->
        val l1 = generatedSequential[context].orEmpty()
        val l2 = generatedParallel[context].orEmpty()
        l1 + l2
    }
}

fun AuroraDeploymentContext.generateResources(runType: RunType): List<Pair<ContextErrors?, Set<AuroraResource>?>> =
    this.features.map { (feature, adc) ->
        val context = this.featureContext[feature] ?: emptyMap()
        try {
            val generatedResources = when (runType) {
                RunType.SEQUENTIAL -> feature.generateSequentially(adc, context)
                RunType.PARALLEL -> feature.generate(adc, context)
            }
            null to generatedResources
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

        val featureResources = eitherErrorsOrFeatures.featureResources()

        val duplicatedNames = eitherErrorsOrFeatures.findDuplicatedNames()
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

        // Mutation!
        val modifyErrors = context.modifyResources(featureResources)

        return@map ErrorsOrFeatures(context, Pair(modifyErrors, featureResources))
    }
}

fun List<Pair<ContextErrors?, Set<AuroraResource>?>>.featureResources(): Set<AuroraResource> = this.mapNotNull {
    it.second
}.flatten().toSet()

fun List<Pair<ContextErrors?, Set<AuroraResource>?>>.findDuplicatedNames(): List<String> {
    val names = this.featureResources().map { "${it.resource.kind}/${it.resource.metadata.name}" }
    val uniqueNames = names.toSet()
    return uniqueNames.filter {
        uniqueName ->
        names.count { uniqueName == it } != 1
    }
}

fun AuroraDeploymentContext.modifyResources(featureResources: Set<AuroraResource>):
    List<ContextCreationErrors> =
    this.features.mapNotNull {
        val featureContext = this.featureContext[it.key] ?: emptyMap()
        try {
            it.key.modify(it.value, featureResources, featureContext)
            null
        } catch (e: Throwable) {
            if (e is ExceptionList) {
                ContextCreationErrors(this.cmd, e.exceptions)
            } else {
                ContextCreationErrors(this.cmd, listOf(e))
            }
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

enum class RunType {
    SEQUENTIAL,
    PARALLEL,
}
