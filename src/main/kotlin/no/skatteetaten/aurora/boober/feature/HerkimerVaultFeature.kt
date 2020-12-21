package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.module.kotlin.convertValue
import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newSecret
import io.fabric8.kubernetes.api.model.Secret
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.addEnvVarsToMainContainers
import no.skatteetaten.aurora.boober.model.findSubKeys
import no.skatteetaten.aurora.boober.service.HerkimerService
import no.skatteetaten.aurora.boober.service.ResourceHerkimer
import no.skatteetaten.aurora.boober.service.ResourceKind
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.boolean
import no.skatteetaten.aurora.boober.utils.createEnvVarRefs
import no.skatteetaten.aurora.boober.utils.ensureEndsWith
import no.skatteetaten.aurora.boober.utils.ensureStartWith
import no.skatteetaten.aurora.boober.utils.jsonMapper
import no.skatteetaten.aurora.boober.utils.oneOf
import org.apache.commons.codec.binary.Base64
import org.springframework.stereotype.Service

private const val FEATURE_FIELD = "credential"

private const val HERKIMER_RESOURCE_KEY = "resources"
private const val HERKIMER_REPONSE_KEY = "herkimerResponse"
private const val HERKIMER_SECRETS_KEY = "secrets"

private val FeatureContext.configuredHerkimerVaultResources: List<HerkimerVaultResource>
    get() = this.getContextKey(
        HERKIMER_RESOURCE_KEY
    )

private val FeatureContext.herkimerResponse: Map<HerkimerVaultResource, List<ResourceHerkimer>>
    get() = this.getContextKey(
        HERKIMER_REPONSE_KEY
    )

private val FeatureContext.herkimerVaultResources: Map<HerkimerVaultResource, List<Secret>>
    get() = this.getContextKey(
        HERKIMER_SECRETS_KEY
    )

@Service
class HerkimerVaultFeature(
    val herkimerService: HerkimerService
) : Feature {
    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return cmd.applicationFiles.findSubKeys(FEATURE_FIELD).flatMap { key ->
            setOf(
                AuroraConfigFieldHandler(
                    "$FEATURE_FIELD/$key/enabled",
                    defaultValue = true,
                    validator = { it.boolean() }),
                AuroraConfigFieldHandler("$FEATURE_FIELD/$key/prefix"),
                AuroraConfigFieldHandler(
                    "$FEATURE_FIELD/$key/serviceClass",
                    validator = { node -> node.oneOf(ResourceKind.values().map { it.toString() }) }),
                AuroraConfigFieldHandler(
                    "$FEATURE_FIELD/$key/multiple",
                    defaultValue = false,
                    validator = { it.boolean() })
            )
        }.toSet()
    }

    override fun createContext(
        spec: AuroraDeploymentSpec,
        cmd: AuroraContextCommand,
        validationContext: Boolean
    ): Map<String, Any> {
        val herkimerVaultResources: List<HerkimerVaultResource> = spec.findAllConfiguredHerkimerResources()
        val resources = mapOf(HERKIMER_RESOURCE_KEY to herkimerVaultResources)

        if (validationContext) {
            return resources
        }

        val herkimerResources: Map<HerkimerVaultResource, List<ResourceHerkimer>> =
            herkimerVaultResources.associateWith {
                herkimerService.getClaimedResources(spec.applicationDeploymentId, it.serviceClass)
            }

        val secrets: Map<HerkimerVaultResource, List<Secret>> = herkimerResources.map { (config, resources) ->
            val secrets = if (!config.multiple) {
                listOf(generateHerkimerSecret(resources.first(), spec))
            } else {
                resources.map {
                    generateHerkimerSecret(it, spec)
                }
            }
            config to secrets
        }.toMap()

        return resources
            .addIfNotNull(HERKIMER_REPONSE_KEY to herkimerResources)
            .addIfNotNull(HERKIMER_SECRETS_KEY to secrets)
    }

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        context: FeatureContext
    ): List<Exception> {

        val configuredHerkimerVaultResources = context.configuredHerkimerVaultResources

        // TODO: Multiple respones with same key and more then 1 are single
        val errors = validateNotExistsResourcesWithMultipleTrueAndFalseWithSamePrefix(configuredHerkimerVaultResources)

        if (!fullValidation) return errors

        val herkimerResponses = context.herkimerResponse

        val fullValidationErrors = herkimerResponses.filter { !it.key.multiple && it.value.size > 1 }.map {
            IllegalStateException("Resource with key=${it.key.key} expects a single result but ${it.value.size} was returned")
        }
        return errors.addIfNotNull(fullValidationErrors)
    }

    override fun generate(adc: AuroraDeploymentSpec, context: FeatureContext): Set<AuroraResource> {
        return context.herkimerVaultResources.values.flatMap { secrets ->
            secrets.map {
                it.generateAuroraResource()
            }
        }.toSet()
    }

    private fun generateHerkimerSecret(response: ResourceHerkimer, adc: AuroraDeploymentSpec): Secret {

        // TODO: Should this be first and should it be called credentials?
        // TODO: What do we do if there are multiple claims?
        val values = jsonMapper().convertValue<Map<String, String>>(response.claims.first().credentials)
        return newSecret {
            metadata {
                name = response.name.ensureStartWith(adc.name, "-")
                    .ensureEndsWith(HERKIMER_RESOURCE_KEY, "-")
                    .toLowerCase().replace("_", "-")
                namespace = adc.namespace
            }
            data = values.mapValues { content -> Base64.encodeBase64String(content.value.toByteArray()) }
        }
    }

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, context: FeatureContext) {
        val envVars = context.herkimerVaultResources.flatMap { (config, secrets) ->
            if (config.multiple) {
                secrets.mapIndexed { index, secret ->
                    secret.createEnvVarRefs(prefix = "${config.prefix}_${index}_")
                }
            } else {
                listOf(secrets.first().createEnvVarRefs(prefix = "${config.prefix}_"))
            }
        }.flatten()

        resources.addEnvVarsToMainContainers(envVars, javaClass)
    }

    private fun AuroraDeploymentSpec.findAllConfiguredHerkimerResources() =
        this.getSubKeyValues(FEATURE_FIELD).mapNotNull {
            this.findConfiguredHerkimerResource(it)
        }

    private fun AuroraDeploymentSpec.findConfiguredHerkimerResource(resourceKey: String): HerkimerVaultResource? {
        val enabled: Boolean = this["$FEATURE_FIELD/$resourceKey/enabled"]
        if (!enabled) return null
        return HerkimerVaultResource(
            key = resourceKey,
            prefix = this.getOrNull<String>("$FEATURE_FIELD/$resourceKey/prefix") ?: resourceKey,
            serviceClass = this["$FEATURE_FIELD/$resourceKey/serviceClass"],
            multiple = this["$FEATURE_FIELD/$resourceKey/multiple"]

        )
    }

    private fun AuroraDeploymentSpec.isFeatureEnabled(): Boolean = this.hasSubKeys(FEATURE_FIELD)

    private fun validateNotExistsResourcesWithMultipleTrueAndFalseWithSamePrefix(configuredHerkimerVaultResources: List<HerkimerVaultResource>): List<IllegalArgumentException> =
        configuredHerkimerVaultResources.groupBy { it.prefix }.mapNotNull { (prefix, vaultResource) ->
            val resourcesWithMultiple = vaultResource.filter { it.multiple }
            val resourcesWithoutMultiple = vaultResource.filter { !it.multiple }

            if (resourcesWithMultiple.isNotEmpty() && resourcesWithoutMultiple.isNotEmpty()) {
                // TODO: Fix better error message
                IllegalArgumentException(
                    """
                        |The configurations with prefix=$prefix has both some configurations 
                        |that expect multiple envvars and some other that does not expect multiple envvars. 
                        |This is not possible. Resources with multiple=true is ${resourcesWithMultiple.map{ it.key}} 
                        |and with multiple=false is ${resourcesWithoutMultiple.map{it.key} }""".trimMargin()
                )
            } else if (resourcesWithoutMultiple.size > 1) {
                IllegalArgumentException("Multiple configurations cannot share the same prefix if they expect a single result. The affected configurations=${resourcesWithoutMultiple.map{ it.key}}")
            } else null
        }
}

data class HerkimerVaultResource(
    val key: String,
    val prefix: String,
    val serviceClass: ResourceKind,
    val multiple: Boolean
)
