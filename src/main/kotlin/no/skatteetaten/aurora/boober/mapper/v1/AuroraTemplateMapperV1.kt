package no.skatteetaten.aurora.boober.mapper.v1

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFields
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

    fun template(auroraConfigFields: AuroraConfigFields): AuroraTemplate {
        return AuroraTemplate(
                parameters = auroraConfigFields.getParameters(parameterHandlers),
                template = auroraConfigFields.extract("template"),
                version = auroraConfigFields.extractIfExistsOrNull("version"),
                replicas = auroraConfigFields.extractIfExistsOrNull("replicas")
        )
    }
}