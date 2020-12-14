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
import no.skatteetaten.aurora.boober.service.HerkimerService
import no.skatteetaten.aurora.boober.service.ResourceKind
import no.skatteetaten.aurora.boober.utils.boolean
import no.skatteetaten.aurora.boober.utils.createEnvVarRefs
import no.skatteetaten.aurora.boober.utils.findResourcesByType
import no.skatteetaten.aurora.boober.utils.jsonMapper
import no.skatteetaten.aurora.boober.utils.notBlank
import no.skatteetaten.aurora.boober.utils.oneOf
import org.apache.commons.codec.binary.Base64
import org.springframework.stereotype.Service

private const val FEATURE_FIELD = "resources"

@Service
class HerkimerVaultFeature(
    val herkimerService: HerkimerService
) : Feature {
    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return header.getSubKeyValues(FEATURE_FIELD).flatMap { key ->
            setOf(
                AuroraConfigFieldHandler(
                    "$FEATURE_FIELD/$key/enabled",
                    defaultValue = true,
                    validator = { it.boolean() }),
                AuroraConfigFieldHandler("$FEATURE_FIELD/$key/prefix", defaultValue = ""),
                AuroraConfigFieldHandler(
                    "$FEATURE_FIELD/$key/serviceClass",
                    validator = { node -> node.oneOf(ResourceKind.values().map { it.toString()})}),
                AuroraConfigFieldHandler(
                    "$FEATURE_FIELD/$key/multiple",
                    defaultValue = false,
                    validator = { it.boolean() })
            )
        }.toSet()
    }

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        cmd: AuroraContextCommand
    ): List<Exception> {
        val configuredHerkimerVaultResources = adc.findAllConfiguredHerkimerResources()

        return validateNotExistsResourcesWithMultipleTrueAndFalseWithSamePrefix(configuredHerkimerVaultResources)
    }

    override fun generate(adc: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraResource> {
        if (!adc.isFeatureEnabled()) return emptySet()

        val herkimerVaultResources = adc.findAllConfiguredHerkimerResources().groupBy { it.prefix }
        val secrets: List<Secret> = herkimerVaultResources.flatMap { (prefix, resources) ->
            //TODO: refactor
            resources.flatMap {
                herkimerService.getClaimedResources(adc.applicationDeploymentId, it.serviceClass).flatMap { resource ->
                    resource.claims.map { claims ->
                        val values = jsonMapper().convertValue<Map<String, String>>(claims.credentials)
                        newSecret {
                            metadata {
                                name = "${adc.name}-${resource.name}-resources"
                                namespace = adc.namespace
                                annotations = mapOf(
                                    "prefix" to prefix
                                )
                            }
                            data = values.mapValues { content -> Base64.encodeBase64String(content.value.toByteArray()) }
                        }
                    }
                }
            }
        }

        return secrets.map {
            it.generateAuroraResource()
        }.toSet()
    }

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, cmd: AuroraContextCommand) {
        if (!adc.isFeatureEnabled()) return

        val herkimerVaultResources = adc.findAllConfiguredHerkimerResources()
        val prefix: String = adc["$FEATURE_FIELD/prefix"]
        val envVars = resources.findResourcesByType<Secret>(suffix = "herkimervault")
            .mapIndexed { index, secret ->
                secret.createEnvVarRefs(prefix = "${prefix}_${index}_", forceUpperCaseForEnvVarName = false)
            }.flatten()

        resources.addEnvVarsToMainContainers(envVars, javaClass)
    }

    private fun AuroraDeploymentSpec.findAllConfiguredHerkimerResources() =
        this.getSubKeyValues("$FEATURE_FIELD").mapNotNull {
            this.findConfiguredHerkimerResource(it)
        }

    private fun AuroraDeploymentSpec.findConfiguredHerkimerResource(resourceKey: String): HerkimerVaultResource? {
        val enabled: Boolean = this["$FEATURE_FIELD/$resourceKey/enabled"]
        if (!enabled) return null
        return HerkimerVaultResource(
            prefix = this["$FEATURE_FIELD/$resourceKey/prefix"],
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
