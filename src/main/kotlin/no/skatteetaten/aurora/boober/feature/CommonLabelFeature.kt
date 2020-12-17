package no.skatteetaten.aurora.boober.feature

import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.addLabels
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.utils.Instants
import no.skatteetaten.aurora.boober.utils.normalizeLabels
import org.springframework.stereotype.Service

@Service
class CommonLabelFeature(val userDetailsProvider: UserDetailsProvider) : Feature {

    // all handlers are in  header
    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return emptySet()
    }

    fun createCommonLabels(adc: AuroraDeploymentSpec): Map<String, String> {
        val labels = mapOf(
            "app" to adc.name,
            "updatedBy" to userDetailsProvider.getAuthenticatedUser().username.replace(":", "-"),
            "updatedAt" to Instants.now.epochSecond.toString(),
            "affiliation" to adc.affiliation,
            "name" to adc.name
        )

        return labels.normalizeLabels()
    }

    override fun modify(
        adc: AuroraDeploymentSpec,
        resources: Set<AuroraResource>,
        context: FeatureContext
    ) {
        val commonLabels = createCommonLabels(adc)
        resources.addLabels(commonLabels, "Added commonLabels", this::class.java)
    }
}
