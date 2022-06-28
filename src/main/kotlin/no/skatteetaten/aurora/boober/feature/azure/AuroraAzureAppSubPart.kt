package no.skatteetaten.aurora.boober.feature.azure

import com.fkorotkov.kubernetes.newObjectMeta
import no.skatteetaten.aurora.boober.feature.ConfiguredRoute
import no.skatteetaten.aurora.boober.feature.azure.AuroraAzureAppSubPart.ConfigPath.clusterTimeout
import no.skatteetaten.aurora.boober.feature.azure.AuroraAzureAppSubPart.ConfigPath.managedRoute
import no.skatteetaten.aurora.boober.feature.name
import no.skatteetaten.aurora.boober.feature.namespace
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.openshift.AuroraAzureApp
import no.skatteetaten.aurora.boober.model.openshift.AzureAppSpec
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecValidationException
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.boolean
import no.skatteetaten.aurora.boober.utils.durationString
import no.skatteetaten.aurora.boober.utils.isListOrEmpty
import no.skatteetaten.aurora.boober.utils.isValidDns

val AuroraDeploymentSpec.azureAppFqdn: String?
    get() {
        return this.replacer.replace(this.getOrNull<String>(AuroraAzureAppSubPart.ConfigPath.azureAppFqdn))
    }

val AuroraDeploymentSpec.isAzureAppFqdnEnabled: Boolean
    get() {
        return azureAppFqdn != null
    }

val AuroraDeploymentSpec.azureAppGroups: List<String>?
    get() {
        //  Somewhat strange construction in order to avoid error with null as typeCast
        val rawGroup = this.getOrNull<Any>(AuroraAzureAppSubPart.ConfigPath.groups) ?: return null
        @Suppress("UNCHECKED_CAST")
        return rawGroup as List<String>
    }

class AuroraAzureAppSubPart {
    object ConfigPath {
        private const val root = "azure"
        const val azureAppFqdn = "$root/azureAppFqdn"
        const val groups = "$root/groups"
        const val managedRoute = "$root/managedRoute"
        const val clusterTimeout = "$root/clusterTimeout"
    }

    fun generate(adc: AuroraDeploymentSpec, azureFeature: AzureFeature): Set<AuroraResource> {
        return adc.azureAppFqdn?.let {
            val clinger: Boolean = adc.isJwtToStsConverterEnabled
            setOf(
                azureFeature.generateResource(
                    AuroraAzureApp(
                        _metadata = newObjectMeta {
                            name = adc.name
                            namespace = adc.namespace
                        },
                        spec = AzureAppSpec(
                            appName = adc.name,
                            azureAppFqdn = adc.azureAppFqdn!!,
                            groups = adc.azureAppGroups!!,
                            noProxy = !clinger
                        )
                    )
                )
            ).addIfNotNull(createManagedRoute(adc, azureFeature))
        } ?: emptySet()
    }

    /**
     * Create a replacement for the webseal route as part of migrating away from webseal
     * @return New azure route in the default router shard
     */
    @Suppress("UNCHECKED_CAST")
    private fun createManagedRoute(
        adc: AuroraDeploymentSpec,
        azureFeature: AzureFeature
    ): AuroraResource {
        val configuredTimeout = adc.getOrNull<String>(clusterTimeout)?.let { timeout ->
            timeout.toIntOrNull()?.let { n -> "${n}s" } ?: timeout
        }
        val annotations = configuredTimeout?.let { mapOf("haproxy.router.openshift.io/timeout" to it) } ?: emptyMap()

        val configuredRoute = ConfiguredRoute(
            objectName = "${adc.name}-managed",
            host = adc[ConfigPath.azureAppFqdn],
            annotations = annotations,
            fullyQualifiedHost = true,
            labels = mapOf(
                "applikasjonsfabrikken" to "true",
                "azureManaged" to "true"
            )
        )

        val openshiftRoute = configuredRoute.generateOpenShiftRoute(
            routeNamespace = adc.namespace,
            serviceName = adc.name,
            routeSuffix = ""
        )
        return azureFeature.generateResource(openshiftRoute)
    }

    fun validate(
        adc: AuroraDeploymentSpec
    ): List<Exception> {
        val errors = mutableListOf<Exception>()
        if (adc.azureAppFqdn == null || adc.azureAppGroups == null) {
            if (!(adc.azureAppFqdn == null && adc.azureAppGroups == null)) {
                errors.add(AuroraDeploymentSpecValidationException("You need to configure either both or none of ${ConfigPath.azureAppFqdn} and ${ConfigPath.groups}"))
            }
        }

        adc.azureAppFqdn?.isValidDns() ?.let { valid: Boolean ->
            if (!valid) {
                errors.add(
                    AuroraDeploymentSpecValidationException(
                        "${ConfigPath.azureAppFqdn} must be a valid dns address"
                    )
                )
            }
        }

        return errors
    }

    fun handlers(): Set<AuroraConfigFieldHandler> =
        setOf(
            AuroraConfigFieldHandler(
                ConfigPath.azureAppFqdn
            ),
            AuroraConfigFieldHandler(
                ConfigPath.groups,
                validator = { it.isListOrEmpty(required = false) }
            ),
            AuroraConfigFieldHandler(
                managedRoute,
                validator = { it.boolean(required = false) }
            ),
            AuroraConfigFieldHandler(
                clusterTimeout,
                validator = { it.durationString() }
            ),
            AuroraConfigFieldHandler(
                "webseal",
                defaultValue = false,
                validator = { it.boolean() },
                canBeSimplifiedConfig = true
            )
        )

    fun getDeprecations(adc: AuroraDeploymentSpec): List<String>? {
        return adc.getOrNull<Boolean>(managedRoute)?.let {
            listOf("$managedRoute is deprecated and should be removed from your configuration.")
        }
    }
}
