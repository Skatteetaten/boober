package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraContextCommand
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.AuroraResource
import no.skatteetaten.aurora.boober.service.Feature
import no.skatteetaten.aurora.boober.service.OpenShiftObjectLabelService
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import org.springframework.stereotype.Service

@Service
class CommonLabelFeature(val userDetailsProvider: UserDetailsProvider) : Feature {

    //all handlers are in  header
    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return emptySet()
    }

    fun createCommonLabels(adc: AuroraDeploymentSpec): Map<String, String> {
        val labels = mapOf(
                "app" to adc.name,
                "updatedBy" to userDetailsProvider.getAuthenticatedUser().username.replace(":", "-"),
                "affiliation" to adc.affiliation,
                "updateInBoober" to "true",
                "name" to adc.name
        )

        return OpenShiftObjectLabelService.toOpenShiftLabelNameSafeMap(labels)
    }

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, cmd: AuroraContextCommand) {
        val commonLabels = createCommonLabels(adc)

        resources.forEach {
            if (it.resource.metadata.namespace != null && !it.header) {
                it.resource.metadata.labels = it.resource.metadata.labels?.addIfNotNull(commonLabels) ?: commonLabels
            }
            if (it.resource.kind == "DeploymentConfig") {
                val dc: DeploymentConfig = jacksonObjectMapper().convertValue(it.resource)
                if (dc.spec.template.metadata == null) {
                    dc.spec.template.metadata = ObjectMeta()
                }
                dc.spec.template.metadata.labels = dc.spec.template.metadata.labels?.addIfNotNull(commonLabels)
                        ?: commonLabels
            }
        }
    }
}
