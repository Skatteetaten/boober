package no.skatteetaten.aurora.boober.feature

import com.fkorotkov.kubernetes.newObjectMeta
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.openshift.AuroreAzureApp
import no.skatteetaten.aurora.boober.model.openshift.AzureAppSpec
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecValidationException
import no.skatteetaten.aurora.boober.utils.boolean
import no.skatteetaten.aurora.boober.utils.isListOrEmpty
import no.skatteetaten.aurora.boober.utils.isValidDns

val AuroraDeploymentSpec.azureAppFqdn: String?
    get() {
        return this.getOrNull(AuroraAzureAppFeature.ConfigPath.azureAppFqdn)
    }

val AuroraDeploymentSpec.azureAppGroups: List<String>?
    get() {
        //  Somewhat strange construction in order to avoid error with null as typeCast
        val rawGroup = this.getOrNull<Any>(AuroraAzureAppFeature.ConfigPath.groups) ?: return null
        return rawGroup as List<String>
    }

@org.springframework.stereotype.Service
class AuroraAzureAppFeature : Feature {
    object ConfigPath {
        private const val root = "azure"
        const val azureAppFqdn = "$root/azureAppFqdn"
        const val groups = "$root/groups"
    }

    override fun enable(header: AuroraDeploymentSpec): Boolean {
        return !header.isJob
    }

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return setOf(
            AuroraConfigFieldHandler(
                ConfigPath.azureAppFqdn,
                validator = { it.isValidDns(required = false) }
            ),
            AuroraConfigFieldHandler(
                ConfigPath.groups,
                validator = { it.isListOrEmpty(required = false) }
            ),
            AuroraConfigFieldHandler(
                JwtToStsConverterFeature.ConfigPath.enabled,
                defaultValue = false,
                validator = { it.boolean(required = false) }
            ),
            AuroraConfigFieldHandler(JwtToStsConverterFeature.ConfigPath.version),
            AuroraConfigFieldHandler(JwtToStsConverterFeature.ConfigPath.discoveryUrl),
            AuroraConfigFieldHandler(JwtToStsConverterFeature.ConfigPath.ivGroupsRequired)
        )
    }

    override fun generate(adc: AuroraDeploymentSpec, context: FeatureContext): Set<AuroraResource> {
        return adc.azureAppFqdn?.let {
            val clinger: Boolean = adc[JwtToStsConverterFeature.ConfigPath.enabled]
            setOf(
                generateResource(
                    AuroreAzureApp(
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

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        context: FeatureContext
    ): List<Exception> {

        if (adc.azureAppFqdn == null || adc.azureAppGroups == null) {
            if (!(adc.azureAppFqdn == null && adc.azureAppGroups == null)) {
                return listOf(AuroraDeploymentSpecValidationException("You need to configure either both or none of ${ConfigPath.azureAppFqdn} and ${ConfigPath.groups}"))
            }
        }
        return ArrayList()
    }
}
