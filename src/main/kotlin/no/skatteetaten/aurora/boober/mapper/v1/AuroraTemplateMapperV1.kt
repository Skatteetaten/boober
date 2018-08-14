package no.skatteetaten.aurora.boober.mapper.v1

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraTemplate

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
        AuroraConfigFieldHandler("version"),
        AuroraConfigFieldHandler("replicas")
    )

    fun findParameters(): List<AuroraConfigFieldHandler> {

        val parameterKeys = applicationFiles.findSubKeys("parameters")

        return parameterKeys.map { parameter ->
            AuroraConfigFieldHandler("parameters/$parameter")
        }
    }

    fun template(auroraDeploymentSpec: AuroraDeploymentSpec): AuroraTemplate {
        return AuroraTemplate(
            parameters = auroraDeploymentSpec.getParameters(parameterHandlers),
            template = auroraDeploymentSpec.extract("template"),
            version = auroraDeploymentSpec.extractOrNull("version"),
            replicas = auroraDeploymentSpec.extractOrNull("replicas")
        )
    }
}