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
import no.skatteetaten.aurora.boober.utils.findResourcesByType
import no.skatteetaten.aurora.boober.utils.jsonMapper
import no.skatteetaten.aurora.boober.utils.oneOf
import org.apache.commons.codec.binary.Base64
import org.springframework.stereotype.Service

private const val FEATURE_FIELD = "resources"
private const val ANNOTATION_HERKIMER_PREFIX = "herkimer.skatteetaten.no/prefix"

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
        val herkimerVaultResources =
            spec.findAllConfiguredHerkimerResources()
        val resources = mapOf("resources" to herkimerVaultResources)
        if (validationContext) {
            return resources
        }

        //TODO: for now this will only work with Database
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

        return resources.addIfNotNull("secrets" to secrets)
    }

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        context: Map<String, Any>
    ): List<Exception> {

        //TODO: Need to validate that each kind is there only once
        val configuredHerkimerVaultResources = context["resources"] as List<HerkimerVaultResource>

        return validateNotExistsResourcesWithMultipleTrueAndFalseWithSamePrefix(configuredHerkimerVaultResources)
        //only deep validate
        //TODO validate that if there are more responses for a single entry it should fail
    }

    override fun generate(adc: AuroraDeploymentSpec, context: Map<String, Any>): Set<AuroraResource> {
        if (!adc.isFeatureEnabled()) return emptySet()

        val herkimerVaultResources = context["secrets"] as Map<HerkimerVaultResource, List<Secret>>

        return herkimerVaultResources.values.flatMap { secrets ->
            secrets.map {
                it.generateAuroraResource()
            }
        }.toSet()
    }

    private fun generateHerkimerSecret(response: ResourceHerkimer, adc: AuroraDeploymentSpec): Secret {

        //TODO: Should this be first and should it be called credentials?
        val values = jsonMapper().convertValue<Map<String, String>>(response.claims.first().credentials)
        return newSecret {
            metadata {
                name = response.name.ensureStartWith(adc.name, "-")
                    .ensureEndsWith("resources", "-")
                    .toLowerCase().replace("_", "-")
                namespace = adc.namespace
            }
            data = values.mapValues { content -> Base64.encodeBase64String(content.value.toByteArray()) }
        }
    }

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, context: Map<String, Any>) {
        if (!adc.isFeatureEnabled()) return

        val herkimerVaultResources = context["secrets"] as Map<HerkimerVaultResource, List<Secret>>

        val envVars =herkimerVaultResources.flatMap { (config, secrets) ->
            if(config.multiple) {
                secrets.mapIndexed { index, secret ->
                    secret.createEnvVarRefs(prefix = "${config.prefix}_${index}_", forceUpperCaseForEnvVarName = false)
                }
            }else {
                listOf(secrets.first().createEnvVarRefs(prefix="${config.prefix}_"))
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
                IllegalArgumentException(
                    """
                        |The resources with prefix=$prefix has both some resources 
                        |that expect multiple envvars and some other that does not expect multiple envvars. 
                        |This is not possible. Resources with multiple=true is ${resourcesWithMultiple.joinToString()} 
                        |and with multiple=false is ${resourcesWithoutMultiple.joinToString()}""".trimMargin()
                )
            } else null
        }
}

data class HerkimerVaultResource(
    val prefix: String,
    val serviceClass: ResourceKind,
    val multiple: Boolean
)

data class HerkimerVaultResourceResponse(
    val request: HerkimerVaultResource,
    val response: ResourceHerkimer
)