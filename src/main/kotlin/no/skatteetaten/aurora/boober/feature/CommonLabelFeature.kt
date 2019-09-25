package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.newOwnerReference
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentContext
import no.skatteetaten.aurora.boober.service.AuroraResource
import no.skatteetaten.aurora.boober.service.Feature
import no.skatteetaten.aurora.boober.service.OpenShiftObjectLabelService
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import org.springframework.stereotype.Service

@Service
class CommonLabelFeature(val userDetailsProvider: UserDetailsProvider) : Feature {

    //all handlers are in  header
    override fun handlers(header: AuroraDeploymentContext): Set<AuroraConfigFieldHandler> {
        return emptySet()
    }

    fun createCommonLabels(adc: AuroraDeploymentContext): Map<String, String> {
        val labels = mapOf(
                "app" to adc.name,
                "updatedBy" to userDetailsProvider.getAuthenticatedUser().username.replace(":", "-"),
                "affiliation" to adc.affiliation,
                "updateInBoober" to "true",
                "booberDeployId" to adc.deployId,
                "name" to adc.name
        )

        return OpenShiftObjectLabelService.toOpenShiftLabelNameSafeMap(labels)
    }

    override fun modify(adc: AuroraDeploymentContext, resources: Set<AuroraResource>) {
        val commonLabels = createCommonLabels(adc)

        resources.forEach {
            it.resource.metadata.labels = it.resource.metadata.labels?.addIfNotNull(commonLabels) ?: commonLabels

            it.resource.metadata.ownerReferences = listOf(
                    newOwnerReference {
                        apiVersion = "skatteetaten.no/v1"
                        kind = "ApplicationDeployment"
                        name = adc.name
                        uid = "123-123" // TODO: fix, probably need to fix this after AD is created in cluster?
                    }
            )
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