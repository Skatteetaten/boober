package no.skatteetaten.aurora.boober.mapper.v1

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraTemplate
import no.skatteetaten.aurora.boober.model.findSubKeys
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient

class AuroraTemplateMapperV1(val applicationFiles: List<AuroraConfigFile>) {


    val parameterHandlers = findParameters()

    val handlers = parameterHandlers + listOf(
            AuroraConfigFieldHandler("template", validator = { json ->

                val template = json?.textValue()

                if (template == null) {
                    IllegalArgumentException("Template is required")
/*
                } else if (!openShiftClient.templateExist(template)) {
                    IllegalArgumentException("Template $template does not exist in openshift namespace")
*/
                } else {
                    null
                }
            })
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
                template = auroraConfigFields.extract("template")
        )
    }
}