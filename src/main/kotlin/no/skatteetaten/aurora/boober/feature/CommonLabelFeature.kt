package no.skatteetaten.aurora.boober.feature

import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.utils.Instants
import no.skatteetaten.aurora.boober.utils.addIfNotNull
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

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, cmd: AuroraContextCommand) {
        val commonLabels = createCommonLabels(adc)

        resources.forEach {
            if (it.resource.metadata.namespace != null && !it.header) {

                it.resource.metadata.labels = commonLabels.addIfNotNull(it.resource.metadata?.labels)

                modifyResource(it, "Added common labels to metadata")
            }
            if (it.resource.kind == "DeploymentConfig") {
                modifyResource(it, "Added common labels to podSpec")
                val dc: DeploymentConfig = it.resource as DeploymentConfig
                if (dc.spec.template.metadata == null) {
                    dc.spec.template.metadata = ObjectMeta()
                }
                dc.spec.template.metadata.labels = commonLabels.addIfNotNull(dc.spec.template.metadata?.labels)
            }
        }
    }
}
