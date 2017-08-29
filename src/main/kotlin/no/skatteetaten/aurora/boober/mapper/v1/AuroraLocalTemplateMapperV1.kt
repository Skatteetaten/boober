package no.skatteetaten.aurora.boober.mapper.v1

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraLocalTemplate

class AuroraLocalTemplateMapperV1(val auroraConfig: AuroraConfig,
                                  val applicationFiles: List<AuroraConfigFile>) {


    val parameterHandlers = findParameters()
    val handlers = parameterHandlers + listOf(
            AuroraConfigFieldHandler("templateFile", validator = { json ->
                val fileName = json?.textValue()
                if (auroraConfig.auroraConfigFiles.none { it.name == fileName }) {
                    IllegalArgumentException("The file named $fileName does not exist in AuroraConfig")
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

    fun localTemplate(auroraConfigFields: AuroraConfigFields): AuroraLocalTemplate {
        return AuroraLocalTemplate(
                parameters = auroraConfigFields.getParameters(parameterHandlers),
                templateJson = extractTemplateJson(auroraConfigFields)
        )
    }

    private fun extractTemplateJson(auroraConfigFields: AuroraConfigFields): JsonNode {
        val templateFile = auroraConfigFields.extract("templateFile").let { fileName ->
            auroraConfig.auroraConfigFiles.find { it.name == fileName }?.contents
        }
        return templateFile ?: throw IllegalArgumentException("templateFile is required")
    }

}