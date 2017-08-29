package no.skatteetaten.aurora.boober.mapper.v1

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraTemplate
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient

class AuroraTemplateMapperV1(val applicationFiles: List<AuroraConfigFile>,
                             val openShiftClient: OpenShiftClient) {


    val parameterHandlers = findParameters()

    val handlers = parameterHandlers + listOf(
            AuroraConfigFieldHandler("template", validator = { json ->

                val template = json?.textValue()

                if (template == null) {
                    IllegalArgumentException("Template is required")
                } else if (!openShiftClient.templateExist(template)) {
                    IllegalArgumentException("Template $template does not exist in openshift namespace")
                } else {
                    null
                }
            })
    )


    private fun findSubKeys(applicationFiles: List<AuroraConfigFile>, name: String): Set<String> {

        return applicationFiles.flatMap {
            if (it.contents.has(name)) {
                it.contents[name].fieldNames().asSequence().toList()
            } else {
                emptyList()
            }
        }.toSet()
    }

    fun findParameters(): List<AuroraConfigFieldHandler> {

        val parameterKeys = findSubKeys(applicationFiles, "parameters")

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