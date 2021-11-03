package no.skatteetaten.aurora.boober.feature.azure

import com.fkorotkov.kubernetes.newObjectMeta
import no.skatteetaten.aurora.boober.feature.ConfiguredRoute
import no.skatteetaten.aurora.boober.feature.azure.AuroraAzureAppSubPart.ConfigPath.managedRoute
import no.skatteetaten.aurora.boober.feature.isWebsealEnabled
import no.skatteetaten.aurora.boober.feature.name
import no.skatteetaten.aurora.boober.feature.namespace
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.openshift.AuroraAzureApp
import no.skatteetaten.aurora.boober.model.openshift.AzureAppSpec
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecValidationException
import no.skatteetaten.aurora.boober.utils.addIfNotNull

val AuroraDeploymentSpec.azureAppFqdn: String?
    get() {
        return this.getOrNull(AuroraAzureAppSubPart.ConfigPath.azureAppFqdn)
    }

val AuroraDeploymentSpec.isAzureAppFqdnEnabled: Boolean
    get() {
        return azureAppFqdn != null
    }

val AuroraDeploymentSpec.azureAppGroups: List<String>?
    get() {
        //  Somewhat strange construction in order to avoid error with null as typeCast
        val rawGroup = this.getOrNull<Any>(AuroraAzureAppSubPart.ConfigPath.groups) ?: return null
        return rawGroup as List<String>
    }

val AuroraDeploymentSpec.isAzureRouteManaged: Boolean
    get() {
        return (this.getOrNull<Boolean?>(managedRoute) == true)
    }

class AuroraAzureAppSubPart {
    object ConfigPath {
        private const val root = "azure"
        const val azureAppFqdn = "$root/azureAppFqdn"
        const val groups = "$root/groups"
        const val managedRoute = "$root/managedRoute"
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
            ).addIfNotNull(createManagedRouteIfApplicable(adc, azureFeature))
        } ?: emptySet()
    }

    /**
     * Create a replacement for the webseal route as part
     * of migrating away from webseal
     * @return New webseal route or null if not applicable
     */
    private fun createManagedRouteIfApplicable(
        adc: AuroraDeploymentSpec,
        azureFeature: AzureFeature
    ): AuroraResource? {
        if (!adc.isAzureRouteManaged) {
            return null
        }
        val configuredRoute = ConfiguredRoute(
            objectName = "${adc.name}-managed",
            host = adc.getOrNull<String>(ConfigPath.azureAppFqdn)!!,
            annotations = emptyMap(),
            fullyQualifiedHost = true,
            labels = mapOf(
                "type" to "webseal",
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
        val errors = ArrayList<Exception>()
        if (adc.azureAppFqdn == null || adc.azureAppGroups == null) {
            if (!(adc.azureAppFqdn == null && adc.azureAppGroups == null)) {
                errors.add(AuroraDeploymentSpecValidationException("You need to configure either both or none of ${ConfigPath.azureAppFqdn} and ${ConfigPath.groups}"))
            }
        }

        if (adc.isAzureRouteManaged && adc.isWebsealEnabled) {
            errors.add(
                AuroraDeploymentSpecValidationException(
                    "You cannot have a managedRoute route and webseal enabled simultaneously"
                )
            )
        }

        return errors
    }
}
