package no.skatteetaten.aurora.boober.feature.azure

import java.util.stream.Stream
import com.fkorotkov.kubernetes.newObjectMeta
import no.skatteetaten.aurora.boober.feature.name
import no.skatteetaten.aurora.boober.feature.namespace
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.findSubKeys
import no.skatteetaten.aurora.boober.model.openshift.ApimSpec
import no.skatteetaten.aurora.boober.model.openshift.AuroraApim
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecValidationException
import no.skatteetaten.aurora.boober.utils.boolean
import no.skatteetaten.aurora.boober.utils.isValidDns
import no.skatteetaten.aurora.boober.utils.startsWithSlash
import no.skatteetaten.aurora.boober.utils.validUrl

val AuroraDeploymentSpec.isApimEnabled: Boolean
    get() {
        return this.getOrNull(AuroraAzureApimSubPart.ConfigPath.enabled) ?: false
    }

val AuroraDeploymentSpec.azureApimPolicies: Map<String, Boolean>?
    get() {
        //  Somewhat strange construction in order to avoid error with null as typeCast
        val rawGroup = this.getOrNull<Any>(AuroraAzureApimSubPart.ConfigPath.policies) ?: return null
        return rawGroup as Map<String, Boolean>
    }

class AuroraAzureApimSubPart {
    object ConfigPath {
        private const val root = "azure/apim"
        const val enabled = "$root/enabled"
        const val path = "$root/path"
        const val openapiUrl = "$root/openapiUrl"
        const val serviceUrl = "$root/serviceUrl"
        const val policies = "$root/policies"
        const val apiHost = "$root/apiHost"
    }

    fun generate(adc: AuroraDeploymentSpec, azureFeature: AzureFeature): Set<AuroraResource> {
        return if (adc.isApimEnabled) {
            val path: String = adc[ConfigPath.path]
            val openapiUrl: String = adc[ConfigPath.openapiUrl]
            val serviceUrl: String = adc[ConfigPath.serviceUrl]
            val apiHost: String = adc[ConfigPath.apiHost]

            setOf(
                azureFeature.generateResource(
                    AuroraApim(
                        _metadata = newObjectMeta {
                            name = adc.name
                            namespace = adc.namespace
                        },
                        spec = ApimSpec(
                            path = path,
                            openapiUrl = openapiUrl,
                            serviceUrl = serviceUrl,
                            policies = adc.azureApimPolicies!!.filterValues { it }.map { (k, _) -> k }.toList(),
                            apiHost = apiHost
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
            Stream.of(
                ConfigPath.path,
                ConfigPath.openapiUrl,
                ConfigPath.serviceUrl,
                ConfigPath.apiHost
            ).forEach {
                if (adc.getOrNull<String>(it) == null) {
                    errors.add(AuroraDeploymentSpecValidationException("You need to configure $it"))
                }
            }

            if (adc.azureApimPolicies == null) {
                errors.add(AuroraDeploymentSpecValidationException("You need to configure ${ConfigPath.policies}"))
            }
        }

        return errors
    }

    fun handlers(cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> =
        setOf(
            AuroraConfigFieldHandler(
                ConfigPath.enabled,
                defaultValue = false,
                validator = { it.boolean(required = false) }
            ),
            AuroraConfigFieldHandler(
                ConfigPath.path,
                validator = { it.startsWithSlash(ConfigPath.path) }
            ),
            AuroraConfigFieldHandler(
                ConfigPath.openapiUrl,
                validator = { it.validUrl(required = false) }
            ),
            AuroraConfigFieldHandler(
                ConfigPath.serviceUrl,
                validator = { it.validUrl(required = false) }
            ),
            AuroraConfigFieldHandler(
                ConfigPath.policies
            ),
            AuroraConfigFieldHandler(
                ConfigPath.apiHost,
                validator = { it.isValidDns() }
            ),
            AuroraConfigFieldHandler("webseal/host") // Needed to be able to run tests
        ) + handlePolicyMap(cmd)

    private fun handlePolicyMap(cmd: AuroraContextCommand) =
        if (cmd.applicationFiles.findSubKeys(ConfigPath.policies).isEmpty()) {
            emptySet()
        } else {
            cmd.applicationFiles.findSubKeys(ConfigPath.policies).flatMap { key ->
                setOf(
                    AuroraConfigFieldHandler(
                        "${ConfigPath.policies}/$key",
                        defaultValue = false,
                        validator = { it.boolean() }
                    )
                )
            }
        }
}
