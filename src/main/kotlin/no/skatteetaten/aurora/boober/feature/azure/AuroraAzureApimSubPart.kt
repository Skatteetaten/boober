package no.skatteetaten.aurora.boober.feature.azure

import com.fkorotkov.kubernetes.newObjectMeta
import no.skatteetaten.aurora.boober.feature.name
import no.skatteetaten.aurora.boober.feature.namespace
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.findSubKeysExpanded
import no.skatteetaten.aurora.boober.model.openshift.ApimPolicy
import no.skatteetaten.aurora.boober.model.openshift.ApimSpec
import no.skatteetaten.aurora.boober.model.openshift.AuroraApim
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecValidationException
import no.skatteetaten.aurora.boober.utils.boolean
import no.skatteetaten.aurora.boober.utils.filterNullValues
import no.skatteetaten.aurora.boober.utils.notBlank
import no.skatteetaten.aurora.boober.utils.notEndsWith
import no.skatteetaten.aurora.boober.utils.startsWith
import no.skatteetaten.aurora.boober.utils.validUrl
import no.skatteetaten.aurora.boober.utils.versionPattern

val AuroraDeploymentSpec.isApimEnabled: Boolean
    get() {
        return this.getOrNull(AuroraAzureApimSubPart.ConfigPath.enabled) ?: false
    }

class AuroraAzureApimSubPart {
    object ConfigPath {
        private const val root = "azure/apim"
        const val enabled = "$root/enabled"
        const val apiName = "$root/apiName"
        const val version = "$root/version"
        const val path = "$root/path"
        const val openApiUrl = "$root/openApiUrl"
        const val serviceUrl = "$root/serviceUrl"
        const val policies = "$root/policies"
    }

    fun generate(adc: AuroraDeploymentSpec, azureFeature: AzureFeature): Set<AuroraResource> {
        return if (adc.isApimEnabled) {
            val apiName: String = adc[ConfigPath.apiName]
            val version: String = adc[ConfigPath.version]
            val path: String = adc[ConfigPath.path]
            val openApiUrl: String = adc[ConfigPath.openApiUrl]
            val serviceUrl: String = adc[ConfigPath.serviceUrl]

            val policies = adc.findSubKeys(ConfigPath.policies).mapNotNull { policyName ->
                // We only include policies which are enabled:
                if (adc.getOrNull<Boolean>("${ConfigPath.policies}/$policyName/enabled") == true) {
                    val parameters = adc.findSubKeys("${ConfigPath.policies}/$policyName/parameters")
                        .associateWith { key ->
                            // Fetch the value for this key:
                            adc.getOrNull<String>("${ConfigPath.policies}/$policyName/parameters/$key")
                        }
                        // Only take entries with a value:
                        .filterNullValues()
                    ApimPolicy(name = policyName, parameters = parameters)
                } else {
                    // This policy is not enabled:
                    null
                }
            }.toSet()

            setOf(
                azureFeature.generateResource(
                    AuroraApim(
                        _metadata = newObjectMeta {
                            name = adc.name
                            namespace = adc.namespace
                        },
                        spec = ApimSpec(
                            apiName = apiName,
                            version = version,
                            path = path,
                            openApiUrl = openApiUrl,
                            serviceUrl = serviceUrl,
                            // We sort for predictability, the order does not matter:
                            policies = policies.sortedBy { it.name }
                        )
                    )
                )
            )
        } else {
            emptySet()
        }
    }

    fun validate(
        adc: AuroraDeploymentSpec
    ): List<Exception> {
        val errors = mutableListOf<Exception>()
        if (adc.isApimEnabled) {
            listOf(
                ConfigPath.apiName,
                ConfigPath.version,
                ConfigPath.path,
                ConfigPath.openApiUrl,
                ConfigPath.serviceUrl,
            ).forEach {
                if (adc.getOrNull<String>(it) == null) {
                    errors.add(AuroraDeploymentSpecValidationException("You need to configure $it"))
                }
            }
        }

        return errors
    }

    fun handlers(applicationFiles: List<AuroraConfigFile>): Set<AuroraConfigFieldHandler> {

        val policyHandlers = applicationFiles.findSubKeysExpanded(ConfigPath.policies)
            .map { fullPolicyPath ->
                createHandlersForPolicy(fullPolicyPath, applicationFiles)
            }
            // We now have a list with a list of handlers for each policy:
            .flatten()

        return setOf(
            AuroraConfigFieldHandler(
                ConfigPath.enabled,
                defaultValue = false,
                validator = { it.boolean(required = false) }
            ),
            AuroraConfigFieldHandler(
                ConfigPath.apiName
            ),
            AuroraConfigFieldHandler(
                ConfigPath.path,
                validator = {
                    it.startsWith("/", "Path should start with a slash.", required = false)
                        ?: it.notEndsWith("/", "Path should not end with a slash.", required = false)
                }
            ),
            AuroraConfigFieldHandler(
                ConfigPath.version,
                validator = { it.versionPattern(required = false) }
            ),
            AuroraConfigFieldHandler(
                ConfigPath.openApiUrl,
                validator = { it.validUrl(required = false, requireHttps = true) }
            ),
            AuroraConfigFieldHandler(
                ConfigPath.serviceUrl,
                validator = { it.validUrl(required = false, requireHttps = true) }
            ),
            AuroraConfigFieldHandler("webseal/host") // Needed to be able to run tests
        ) + policyHandlers
    }

    private fun createHandlersForPolicy(
        fullPolicyPath: String,
        applicationFiles: List<AuroraConfigFile>
    ): List<AuroraConfigFieldHandler> {
        // First create handler for the enabled toggle:
        val enabledHandler = AuroraConfigFieldHandler(
            "$fullPolicyPath/enabled",
            validator = { it.boolean(required = true) }
        )
        // Then add handlers for all parameters found for this policy:
        val parameterHandlers = applicationFiles
            .findSubKeysExpanded("$fullPolicyPath/parameters")
            .map { fullPolicyParameterPath ->
                AuroraConfigFieldHandler(
                    fullPolicyParameterPath,
                    validator = { it.notBlank("Please provide value for $fullPolicyParameterPath!") }
                )
            }
        // Create a set of all the handlers created for this policy:
        return parameterHandlers + enabledHandler
    }
}
