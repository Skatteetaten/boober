package no.skatteetaten.aurora.boober.mapper.v1

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraTemplate
import no.skatteetaten.aurora.boober.model.AuroraTemplateResource
import no.skatteetaten.aurora.boober.model.AuroraTemplateResources
import no.skatteetaten.aurora.boober.utils.pattern

class AuroraTemplateMapperV1(val applicationFiles: List<AuroraConfigFile>) {

    val parameterHandlers = findParameters()

    val handlers = parameterHandlers + listOf(
        AuroraConfigFieldHandler("template", validator = { json ->

            val template = json?.textValue()

            if (template == null) {
                IllegalArgumentException("Template is required")
            } else {
                null
            }
        }),
        AuroraConfigFieldHandler("version", validator = {
            it.pattern(
                "^[\\w][\\w.-]{0,127}$",
                "Version must be a 128 characters or less, alphanumeric and can contain dots and dashes",
                false
            )
        }),
        AuroraConfigFieldHandler("replicas"),
        AuroraConfigFieldHandler("resources/cpu/min"),
        AuroraConfigFieldHandler("resources/cpu/max"),
        AuroraConfigFieldHandler("resources/memory/min"),
        AuroraConfigFieldHandler("resources/memory/max")
    )

    fun findParameters(): List<AuroraConfigFieldHandler> {
        return applicationFiles.findSubKeysExpanded("parameters").map {
            AuroraConfigFieldHandler(it)
        }
    }

    fun template(auroraDeploymentSpec: AuroraDeploymentSpec): AuroraTemplate {
        return AuroraTemplate(
            parameters = auroraDeploymentSpec.getParameters(parameterHandlers),
            template = auroraDeploymentSpec["template"],
            version = auroraDeploymentSpec.getOrNull("version"),
            replicas = auroraDeploymentSpec.getOrNull("replicas"),
            resources = AuroraTemplateResources(
                request = AuroraTemplateResource(
                    cpu = auroraDeploymentSpec.getOrNull("resources/cpu/min"),
                    memory = auroraDeploymentSpec.getOrNull("resources/memory/min")
                ),
                limit = AuroraTemplateResource(
                    cpu = auroraDeploymentSpec.getOrNull("resources/cpu/max"),
                    memory = auroraDeploymentSpec.getOrNull("resources/memory/max")
                )
            )
        )
    }
}