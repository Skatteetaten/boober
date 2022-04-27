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

val AuroraDeploymentSpec.isApimEnabled: Boolean
    get() {
        return this.getOrNull(AuroraAzureApimSubPart.ConfigPath.enabled) ?: false
    }

class AuroraAzureApimSubPart {
    object ConfigPath {
        private const val root = "azure/apim"
        const val enabled = "$root/enabled"
        const val apiName = "$root/apiName"
        const val path = "$root/path"
        const val versions = "$root/versions"

        // Inside a single version:
        const val openApiUrl = "/openApiUrl"
        const val serviceUrl = "/serviceUrl"
        const val policies = "/policies"
    }

    fun generate(adc: AuroraDeploymentSpec, azureFeature: AzureFeature): Set<AuroraResource> {
        return if (adc.isApimEnabled) {
            val apiName: String = adc[ConfigPath.apiName]
            val path: String = adc[ConfigPath.path]

            adc.findSubKeys(ConfigPath.versions).mapNotNull { version ->

                val versionPath = ConfigPath.versions + "/" + version
                if (adc.getOrNull<Boolean>("$versionPath/enabled") == true) {

                    val policies = getApiVersionPolicies(adc, versionPath)

                    azureFeature.generateResource(
                        AuroraApim(
                            _metadata = newObjectMeta {
                                name = adc.name + "-" + apiName + "-" + version
                                namespace = adc.namespace
                            },
                            spec = ApimSpec(
                                apiName = apiName,
                                version = version,
                                path = path,
                                openApiUrl = adc[versionPath + ConfigPath.openApiUrl],
                                serviceUrl = adc[versionPath + ConfigPath.serviceUrl],
                                // We sort for predictability, the order does not matter:
                                policies = policies.sortedBy { it.name }
                            )
                        )
                    )
                } else {
                    // This version is not enabled:
                    null
                }
            }.toSet()
        } else {
            emptySet()
        }
    }

    private fun getApiVersionPolicies(
        adc: AuroraDeploymentSpec,
        versionPath: String
    ) = adc.findSubKeys(versionPath + ConfigPath.policies).mapNotNull { policyName ->
        // We only include policies which are enabled:
        if (adc.getOrNull<Boolean>("$versionPath${ConfigPath.policies}/$policyName/enabled") == true) {
            val parameters =
                adc.findSubKeys("$versionPath${ConfigPath.policies}/$policyName/parameters")
                    .associateWith { key ->
                        // Fetch the value for this key:
                        adc.getOrNull<String>("$versionPath${ConfigPath.policies}/$policyName/parameters/$key")
                    }
                    // Only take entries with a value:
                    .filterNullValues()
            ApimPolicy(name = policyName, parameters = parameters)
        } else {
            // This policy is not enabled:
            null
        }
    }

    fun validate(
        adc: AuroraDeploymentSpec
    ): List<Exception> {
        val errors = mutableListOf<Exception>()
        if (adc.isApimEnabled) {
            listOf(
                ConfigPath.apiName,
                ConfigPath.path
            ).forEach {
                if (adc.getOrNull<String>(it) == null) {
                    errors.add(AuroraDeploymentSpecValidationException("You need to configure $it"))
                }
            }

            adc.findSubKeys(ConfigPath.versions).forEach { version ->
                if (!Regex("v\\d{1,3}").matches(version)) {
                    errors.add(AuroraDeploymentSpecValidationException("Invalid version $version. Please specify version with vX. Examples v1, v2 etc."))
                }
            }
        }

        return errors
    }

    fun handlers(applicationFiles: List<AuroraConfigFile>): Set<AuroraConfigFieldHandler> {

        val versionHandlers = applicationFiles.findSubKeysExpanded(ConfigPath.versions)
            .map { fullVersionPath -> createHandlersForVersion(fullVersionPath, applicationFiles) }
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
            AuroraConfigFieldHandler("webseal/host") // Needed to be able to run tests
        ) + versionHandlers
    }

    private fun createHandlersForVersion(
        fullVersionPath: String,
        applicationFiles: List<AuroraConfigFile>
    ): Set<AuroraConfigFieldHandler> {

        val policyHandlers = applicationFiles.findSubKeysExpanded(fullVersionPath + ConfigPath.policies)
            .map { fullPolicyPath ->
                createHandlersForPolicy(fullPolicyPath, applicationFiles)
            }
            // We now have a list with a list of handlers for each policy:
            .flatten()

        return setOf(
            AuroraConfigFieldHandler(
                "$fullVersionPath/enabled",
                defaultValue = false,
                validator = { it.boolean(required = false) }
            ),
            AuroraConfigFieldHandler(
                fullVersionPath + ConfigPath.openApiUrl,
                validator = { it.validUrl(required = false, requireHttps = true) }
            ),
            AuroraConfigFieldHandler(
                fullVersionPath + ConfigPath.serviceUrl,
                validator = { it.validUrl(required = false, requireHttps = true) }
            )
        ) + policyHandlers
    }

    private fun createHandlersForPolicy(
        fullPolicyPath: String,
        applicationFiles: List<AuroraConfigFile>
    ): Set<AuroraConfigFieldHandler> {
        // First create handler for the enabled toggle:
        val enabledHandler = AuroraConfigFieldHandler(
            "$fullPolicyPath/enabled",
            defaultValue = false,
            validator = { it.boolean(required = false) }
        )
        // Then add handlers for all parameters found for this policy:
        val parameterHandlers = applicationFiles
            .findSubKeysExpanded("$fullPolicyPath/parameters")
            .map { fullPolicyParameterPath ->
                AuroraConfigFieldHandler(
                    fullPolicyParameterPath,
                    validator = { it.notBlank("Please provide value for $fullPolicyParameterPath!") }
                )
            }.toSet()
        // Create a set of all the handlers created for this policy:
        return parameterHandlers + enabledHandler
    }
}
