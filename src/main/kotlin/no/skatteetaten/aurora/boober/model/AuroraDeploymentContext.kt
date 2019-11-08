package no.skatteetaten.aurora.boober.model

import mu.KotlinLogging
import no.skatteetaten.aurora.boober.feature.Feature
import no.skatteetaten.aurora.boober.service.ContextErrors
import no.skatteetaten.aurora.boober.service.ExceptionList
import no.skatteetaten.aurora.boober.service.MultiApplicationValidationException
import no.skatteetaten.aurora.boober.utils.UUIDGenerator

private val logger = KotlinLogging.logger { }

fun AuroraDeploymentContext.validate(fullValidation: Boolean): Map<Feature, List<java.lang.Exception>> {
    return features.mapValues {
        try {
            it.key.validate(it.value, fullValidation, this.cmd)
        } catch (e: Exception) {
            if (e is ExceptionList) {
                e.exceptions
            } else {
                listOf(e)
            }
        }
    }
}

// FEATURE: Should this be in model Or in service somewhere?
// FEATURE: Unit test errors here
fun List<AuroraDeploymentContext>.createDeployCommand(deploy: Boolean): List<AuroraDeployCommand> {
    val result: List<Pair<List<ContextErrors>, AuroraDeployCommand?>> = this.map { context ->
        val (errors, resourceResults) = context.createResources()
        when {
            errors.isNotEmpty() -> errors to null
            resourceResults == null -> listOf(
                ContextErrors(
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

    val resourceErrors = result.flatMap { it.first }
    if (resourceErrors.isNotEmpty()) {

        val errorMessages = resourceErrors.flatMap { err ->
            err.errors.map { it.localizedMessage }
        }
        logger.debug("Validation errors: ${errorMessages.joinToString("\n", prefix = "\n")}")

        throw MultiApplicationValidationException(resourceErrors)
    }

    return result.mapNotNull { it.second }
}

fun AuroraDeploymentContext.createResources(): Pair<List<ContextErrors>, Set<AuroraResource>?> {

    val eitherErrorsOrFeatures: List<Pair<ContextErrors?, Set<AuroraResource>?>> = features.map {
        try {
            null to it.key.generate(it.value, this.cmd)
        } catch (e: Throwable) {
            if (e is ExceptionList) {
                ContextErrors(this.cmd, e.exceptions) to null
            } else {
                ContextErrors(this.cmd, listOf(e)) to null
            }
        }
    }

    // There was some errors when generating so we gather then up and return them and no resources
    val errors = eitherErrorsOrFeatures.mapNotNull { it.first }
    if (errors.isNotEmpty()) {
        return errors to null
    }

    val featureResources = eitherErrorsOrFeatures.mapNotNull { it.second }.flatten().toSet()
    val uniqueNames = featureResources.map { "${it.resource.kind}/${it.resource.metadata.name}" }

    val namesSet = uniqueNames.toSet()
    val namesString = uniqueNames.joinToString(",")
    if (namesSet.size != uniqueNames.size) {
        val error: List<ContextErrors> =
            listOf(ContextErrors(this.cmd, listOf(RuntimeException("Resource with duplicate kind/name=$namesString"))))
        return error to featureResources
    }

    // Mutation!
    val modifyErrors = this.features.mapNotNull {
        try {
            it.key.modify(it.value, featureResources, this.cmd)
            null
        } catch (e: Throwable) {
            if (e is ExceptionList) {
                ContextErrors(this.cmd, e.exceptions)
            } else {
                ContextErrors(this.cmd, listOf(e))
            }
        }
    }

    return modifyErrors to featureResources
}

typealias FeatureSpec = Map<Feature, AuroraDeploymentSpec>

data class AuroraDeploymentContext(
    val spec: AuroraDeploymentSpec,
    val cmd: AuroraContextCommand,
    val features: FeatureSpec,
    val featureHandlers: Map<Feature, Set<AuroraConfigFieldHandler>>
)
