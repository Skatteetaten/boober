package no.skatteetaten.aurora.boober.model

import no.skatteetaten.aurora.boober.feature.Feature
import no.skatteetaten.aurora.boober.service.ContextErrors
import no.skatteetaten.aurora.boober.service.ExceptionList

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
