package no.skatteetaten.aurora.boober.model

import mu.KotlinLogging
import no.skatteetaten.aurora.boober.feature.Feature
import no.skatteetaten.aurora.boober.service.ContextErrors
import no.skatteetaten.aurora.boober.service.ExceptionList
import no.skatteetaten.aurora.boober.service.MultiApplicationValidationException
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

fun List<AuroraDeploymentContext>.createDeployCommand(deploy: Boolean): List<AuroraDeployCommand> {
    val result: List<Pair<List<ContextErrors>, AuroraDeployCommand?>> = this.parallelMap { context ->
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

        /* just throw the exception...
        resourceErrors.forEach { it.errors.forEach { err ->
            throw err
        } }
         */
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

        val context = this.featureContext[it.key] ?: emptyMap()
        try {
            null to it.key.generate(it.value, context)
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
    val names = featureResources.map { "${it.resource.kind}/${it.resource.metadata.name}" }

    val uniqueNames = names.toSet()

    val duplicatedNames = uniqueNames.filter { uniqueName ->
        names.count { uniqueName == it } != 1
    }

    // TODO: This failed Test this
    if (duplicatedNames.isNotEmpty()) {
        val namesString = duplicatedNames.joinToString(", ").toLowerCase()
        val error: List<ContextErrors> =
            listOf(ContextErrors(this.cmd, listOf(RuntimeException("The following resources are generated more then once $namesString"))))
        return error to featureResources
    }

    // TODO: Message:     Resource with duplicate kind/name=Service/activemq-3-native,Service/activemq-3-admin,Route/activemq-3,ImageStream/amq-classic-single,DeploymentConfig/activemq-3,Secret/activemq-3-cert,Route/activemq-3,ApplicationDeployment/activemq-3,ProjectRequest/paas-m89870,Namespace/paas-m89870,RoleBinding/admin

    // Mutation!
    val modifyErrors = this.features.mapNotNull {
        val context = this.featureContext[it.key] ?: emptyMap()
        try {
            it.key.modify(it.value, featureResources, context)
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
    val featureContext: Map<Feature, Map<String, Any>>,
    val warnings: List<String> = emptyList()
)
