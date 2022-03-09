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
                if (adc.getOrNull<Boolean>("${ConfigPath.policies}/$policyName/enabled") == true) {
                    // Here we could read in any additional properties of the policy.
                    ApimPolicy(name = policyName)
                } else {
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

        val policyHandlers = applicationFiles.findSubKeysExpanded(ConfigPath.policies).map { policyName ->
            AuroraConfigFieldHandler(
                "$policyName/enabled",
                validator = { it.boolean(required = true) }
            )
        }.toSet()

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
}
