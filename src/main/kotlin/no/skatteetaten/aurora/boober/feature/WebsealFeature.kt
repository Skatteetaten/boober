package no.skatteetaten.aurora.boober.feature

import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.boolean
import no.skatteetaten.aurora.boober.utils.filterNullValues
import org.springframework.stereotype.Service

@Service
class WebsealFeature : Feature {

    override fun enable(header: AuroraDeploymentSpec): Boolean {
        return header.type != TemplateType.job
    }

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return setOf(
            AuroraConfigFieldHandler(
                "webseal",
                defaultValue = false,
                validator = { it.boolean() },
                canBeSimplifiedConfig = true
            ),
            AuroraConfigFieldHandler("webseal/host"),
            AuroraConfigFieldHandler("webseal/strict", validator = { it.boolean() }),
            AuroraConfigFieldHandler("webseal/roles")
        )
    }

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, cmd: AuroraContextCommand) {
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
                    modifyResource(it, "Set webseal annotations")
                    val allAnnotations = it.resource.metadata.annotations?.addIfNotNull(annotations) ?: annotations
                    it.resource.metadata.annotations = allAnnotations
                }
            }
        }
    }

    fun willCreateResource(adc: AuroraDeploymentSpec): Boolean {
        return adc.featureEnabled("webseal") {
            true
        } ?: false
    }

    fun shouldWarnAboutFeature(adc: AuroraDeploymentSpec): Boolean = adc.getOrNull("webseal/strict") ?: true
}
