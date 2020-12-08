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
import org.apache.commons.codec.binary.Base64
import org.springframework.stereotype.Service

private const val FEATURE_FIELD = "vault"

@Service
class HerkimerVaultFeature(
    val herkimerService: HerkimerService
) : Feature {
    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return setOf(
            AuroraConfigFieldHandler("$FEATURE_FIELD/enabled", validator = { it.boolean() }),
            AuroraConfigFieldHandler("$FEATURE_FIELD/prefix", defaultValue = "")

        )
    }

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        cmd: AuroraContextCommand
    ): List<Exception> {
        return emptyList()
    }

    override fun generate(adc: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraResource> {
        if (!adc.isFeatureEnabled()) return emptySet()

        val nameAndCredentials =
            herkimerService.getClaimedResources(adc.applicationDeploymentId, ResourceKind.ManagedPostgresDatabase)
                .associate { resource ->
                    resource.name to resource.claims.firstOrNull()?.credentials
                }.mapValues { (name, credentials) ->
                    credentials ?: TODO()
                    if (credentials.isObject) {
                        jsonMapper().convertValue<Map<String, String>>(credentials)
                    } else {
                        TODO("NOT supported, fix it")
                    }
                }

        val secrets = nameAndCredentials.map { (resourceName, credentials) ->
            newSecret {
                metadata {
                    name = "${adc.name}-$resourceName-herkimervault"
                    namespace = adc.namespace
                }
                data = credentials.mapValues { Base64.encodeBase64String(it.value.toByteArray()) }
            }
        }

        return secrets.map {
            it.generateAuroraResource()
        }.toSet()
    }

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, cmd: AuroraContextCommand) {
        if (!adc.isFeatureEnabled()) return
        val prefix: String = adc["$FEATURE_FIELD/prefix"]
        val envVars = resources.findResourcesByType<Secret>(suffix = "herkimervault")
            .mapIndexed { index, secret ->
                secret.createEnvVarRefs(prefix = "${prefix}_${index}_", forceUpperCaseForEnvVarName = false)
            }.flatten()

        resources.addEnvVarsToMainContainers(envVars, javaClass)
    }

    private fun AuroraDeploymentSpec.isFeatureEnabled(): Boolean = this.hasSubKeys(FEATURE_FIELD)
}
