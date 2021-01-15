package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.module.kotlin.convertValue
import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newSecret
import io.fabric8.kubernetes.api.model.EnvVar
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
import no.skatteetaten.aurora.boober.utils.boolean
import no.skatteetaten.aurora.boober.utils.createEnvVarRefs
import no.skatteetaten.aurora.boober.utils.ensureEndsWith
import no.skatteetaten.aurora.boober.utils.ensureStartWith
import no.skatteetaten.aurora.boober.utils.jsonMapper
import no.skatteetaten.aurora.boober.utils.oneOf
import org.apache.commons.codec.binary.Base64
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.stereotype.Service

private const val FEATURE_FIELD = "credentials"

private const val HERKIMER_SECRETS_KEY = "secrets"

private val FeatureContext.configuredHerkimerVaultCredentials: List<HerkimerVaultCredential>
    get() = this.getContextKey(
        FEATURE_FIELD
    )

private val FeatureContext.herkimerVaultCredentials: Map<String, CredentialsAndSecretsWithSharedPrefix>
    get() = this.getContextKey(
        HERKIMER_SECRETS_KEY
    )

typealias CredentialsAndSecretsWithSharedPrefix = List<Pair<HerkimerVaultCredential, List<Secret>>>

/*
* Fetches credentials belonging to the application from herkimer. These are injected into PodSpec as SecretEnvVars
*/
@ConditionalOnBean(HerkimerService::class)
@Service
class HerkimerVaultFeature(
    val herkimerService: HerkimerService,
    @Value("\${application.deployment.id}") val booberApplicationdeploymentId: String
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
                    "$FEATURE_FIELD/$key/resourceKind",
                    validator = { node -> node.oneOf(ResourceKind.values().map { it.toString() }) }),
                AuroraConfigFieldHandler(
                    "$FEATURE_FIELD/$key/multiple",
                    defaultValue = false,
                    validator = { it.boolean() }),
                AuroraConfigFieldHandler(
                    "$FEATURE_FIELD/$key/uppercaseEnvVarsSuffix",
                    defaultValue = true,
                    validator = { it.boolean() })
            )
        }.toSet()
    }

    override fun createContext(
        spec: AuroraDeploymentSpec,
        cmd: AuroraContextCommand,
        validationContext: Boolean
    ): FeatureContext {
        val configuredHerkimerVaultCredentials: List<HerkimerVaultCredential> = spec.findAllConfiguredCredentials()
        val featureContext = mapOf(FEATURE_FIELD to configuredHerkimerVaultCredentials)

        if (validationContext) {
            return featureContext
        }

        val secrets: Map<String, CredentialsAndSecretsWithSharedPrefix> =
            configuredHerkimerVaultCredentials.map { vaultCredential ->
                vaultCredential to herkimerService.getClaimedResources(
                    booberApplicationdeploymentId,
                    vaultCredential.resourceKind
                )
                    .map { generateHerkimerSecret(it, spec) }
            }.groupBy { (vaultCredential, secrets) -> vaultCredential.prefix }

        return featureContext + mapOf(HERKIMER_SECRETS_KEY to secrets)
    }

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        context: FeatureContext
    ): List<Exception> {
        val configuredHerkimerVaultCredentials = context.configuredHerkimerVaultCredentials

        val sharedPrefixConstraintViolations = configuredHerkimerVaultCredentials.validateSharedPrefixConstraints()

        if (!fullValidation) return sharedPrefixConstraintViolations

        context.herkimerVaultCredentials
        val herkimerResponses = context.herkimerVaultCredentials.values.flatten()

        val configuredSingleCredentialErrors = herkimerResponses
            .filter { !it.first.multiple && it.second.size > 1 }
            .map { (vaultCredential, secrets) ->
                IllegalStateException("Configured credential=${vaultCredential.key} is configured as multiple=false, but ${secrets.size} was returned")
            }

        return sharedPrefixConstraintViolations + configuredSingleCredentialErrors
    }

    override fun generate(adc: AuroraDeploymentSpec, context: FeatureContext): Set<AuroraResource> {
        return context.herkimerVaultCredentials.values.flatten().flatMap { (vaultCredential, secrets) ->
            secrets.map { it.generateAuroraResource() }
        }.toSet()
    }

    private fun generateHerkimerSecret(response: ResourceHerkimer, adc: AuroraDeploymentSpec): Secret {
        val values = jsonMapper().convertValue<Map<String, String>>(response.claims.first().credentials)
        return newSecret {
            metadata {
                name = response.name.ensureStartWith(adc.name, "-")
                    .ensureEndsWith(FEATURE_FIELD, "-")
                    .toLowerCase().replace("_", "-")
                namespace = adc.namespace
            }
            data = values.mapValues { content -> Base64.encodeBase64String(content.value.toByteArray()) }
        }
    }

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, context: FeatureContext) {
        val envVars = context.herkimerVaultCredentials.flatMap { (prefix, credentialsAndSecretsWithSharedPrefix) ->
            if (credentialsAndSecretsWithSharedPrefix.isMultiple()) {
                credentialsAndSecretsWithSharedPrefix.generateEnvVarsForMultipleValues(prefix)
            } else {
                credentialsAndSecretsWithSharedPrefix.generateEnvVarsForSingleValue(prefix)
            }
        }

        resources.addEnvVarsToMainContainers(envVars, javaClass)
    }

    private fun CredentialsAndSecretsWithSharedPrefix.isMultiple() =
        this.any { (vaultCredential, _) -> vaultCredential.multiple }

    private fun CredentialsAndSecretsWithSharedPrefix.credentialAndSingleSecret(): Pair<HerkimerVaultCredential, Secret> {
        val (credential, secrets) = this.first()
        return credential to secrets.first()
    }

    private fun CredentialsAndSecretsWithSharedPrefix.generateEnvVarsForSingleValue(prefix: String): List<EnvVar> =
        this.credentialAndSingleSecret()
            .let { (vaultCredential, secret) ->
                secret.createEnvVarRefs(prefix = "${prefix}_", uppercaseSuffix = vaultCredential.uppercaseEnvVarsSuffix)
            }

    private fun CredentialsAndSecretsWithSharedPrefix.generateEnvVarsForMultipleValues(prefix: String): List<EnvVar> =
        this.flatMap { (vaultCredential, secrets) ->
            secrets.map { vaultCredential to it }
        }.mapIndexed { index, (vaultCredential, secret) ->
            secret.createEnvVarRefs(
                prefix = "${prefix}_${index}_",
                uppercaseSuffix = vaultCredential.uppercaseEnvVarsSuffix
            )
        }.flatten()

    private fun AuroraDeploymentSpec.findAllConfiguredCredentials() =
        this.getSubKeyValues(FEATURE_FIELD).mapNotNull {
            this.findConfiguredCredential(it)
        }

    private fun AuroraDeploymentSpec.findConfiguredCredential(credentialKey: String): HerkimerVaultCredential? {
        val enabled: Boolean = this["$FEATURE_FIELD/$credentialKey/enabled"]
        if (!enabled) return null
        return HerkimerVaultCredential(
            key = credentialKey,
            prefix = this.getOrNull<String>("$FEATURE_FIELD/$credentialKey/prefix") ?: credentialKey,
            resourceKind = this["$FEATURE_FIELD/$credentialKey/resourceKind"],
            multiple = this["$FEATURE_FIELD/$credentialKey/multiple"],
            uppercaseEnvVarsSuffix = this["$FEATURE_FIELD/$credentialKey/uppercaseEnvVarsSuffix"]
        )
    }

    private fun List<HerkimerVaultCredential>.validateSharedPrefixConstraints(): List<IllegalArgumentException> =
        this.groupBy { it.prefix }
            .mapNotNull { (prefix, vaultCredentials) ->

                val (credentialsConfiguredAsMultiple, credentialsConfiguredAsSingle) = vaultCredentials.partition { it.multiple }

                when {
                    credentialsConfiguredAsMultiple.isNotEmpty() -> {
                        if (credentialsConfiguredAsSingle.isEmpty()) {

                            val (uppercaseEnvVarsSuffix, notUppercaseEnvVarsSuffix) = vaultCredentials.partition { it.uppercaseEnvVarsSuffix }

                            if (uppercaseEnvVarsSuffix.isNotEmpty() && notUppercaseEnvVarsSuffix.isNotEmpty()) {
                                IllegalArgumentException(
                                    "The shared prefix=$prefix has been configured with both uppercaseEnvVarsSuffix=false and uppercaseEnvVarsSuffix=true." +
                                        " This combination is not allowed." +
                                        " uppercaseEnvVarsSuffix=true is configured for ${uppercaseEnvVarsSuffix.map { it.key }}" +
                                        " uppercaseEnvVarsSuffix=false is configured for ${notUppercaseEnvVarsSuffix.map { it.key }}"
                                )
                            } else null
                        } else {
                            IllegalArgumentException(
                                "The shared prefix=$prefix has been configured with both multiple=false and multiple=true." +
                                    " It is not feasible to generate EnvVars when both multiple and single is expected." +
                                    " multiple=false is configured for ${credentialsConfiguredAsSingle.map { it.key }}" +
                                    " multiple=true is configured for ${credentialsConfiguredAsMultiple.map { it.key }}"
                            )
                        }
                    }
                    credentialsConfiguredAsSingle.size > 1 -> {
                        IllegalArgumentException(
                            "Multiple configurations cannot share the same prefix=$prefix if they expect a single result(multiple=false)." +
                                " The affected configurations=${credentialsConfiguredAsSingle.map { it.key }}"
                        )
                    }
                    else -> null
                }
            }
}

data class HerkimerVaultCredential(
    val key: String,
    val prefix: String,
    val resourceKind: ResourceKind,
    val multiple: Boolean,
    val uppercaseEnvVarsSuffix: Boolean
)
