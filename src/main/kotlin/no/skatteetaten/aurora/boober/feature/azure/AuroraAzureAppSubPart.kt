package no.skatteetaten.aurora.boober.feature.azure

import com.fkorotkov.kubernetes.newObjectMeta
import no.skatteetaten.aurora.boober.feature.name
import no.skatteetaten.aurora.boober.feature.namespace
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.openshift.AuroraAzureApp
import no.skatteetaten.aurora.boober.model.openshift.AzureAppSpec
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecValidationException

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

class AuroraAzureAppSubPart {
    object ConfigPath {
        private const val root = "azure"
        const val azureAppFqdn = "$root/azureAppFqdn"
        const val groups = "$root/groups"
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
            )
        } ?: emptySet()
    }

    fun validate(
        adc: AuroraDeploymentSpec
    ): List<Exception> {
        if (adc.azureAppFqdn == null || adc.azureAppGroups == null) {
            if (!(adc.azureAppFqdn == null && adc.azureAppGroups == null)) {
                return listOf(AuroraDeploymentSpecValidationException("You need to configure either both or none of ${ConfigPath.azureAppFqdn} and ${ConfigPath.groups}"))
            }
        }
        return ArrayList()
    }
}
