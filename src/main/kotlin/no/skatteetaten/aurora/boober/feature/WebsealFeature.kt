package no.skatteetaten.aurora.boober.feature

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentContext
import no.skatteetaten.aurora.boober.service.AuroraResource
import no.skatteetaten.aurora.boober.service.Feature
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.filterNullValues
import org.springframework.stereotype.Service

// TODO: Integration with webseal provisioner
@Service
class WebsealFeature() : Feature {
    override fun handlers(header: AuroraDeploymentContext): Set<AuroraConfigFieldHandler> {
        return setOf(
                AuroraConfigFieldHandler("webseal", defaultValue = false, canBeSimplifiedConfig = true),
                AuroraConfigFieldHandler("webseal/host"),
                AuroraConfigFieldHandler("webseal/roles")
        )
    }


    override fun modify(adc: AuroraDeploymentContext, resources: Set<AuroraResource>) {
        adc.featureEnabled("webseal") { field ->
            val roles = adc.getDelimitedStringOrArrayAsSet("$field/roles", ",")
                    .takeIf { it.isNotEmpty() }?.joinToString(",")
            val host = adc.getOrNull<String>("$field/host") ?: "${adc.name}-${adc.namespace}"
            val annotations = mapOf(
                    "sprocket.sits.no/service.webseal" to host,
                    "sprocket.sits.no/service.webseal-roles" to roles
            ).filterNullValues()

            resources.forEach {
                if (it.resource.kind == "Service") {
                    val allAnnotations = it.resource.metadata.annotations.addIfNotNull(annotations)
                    it.resource.metadata.annotations = allAnnotations
                }
            }
        }
    }
}