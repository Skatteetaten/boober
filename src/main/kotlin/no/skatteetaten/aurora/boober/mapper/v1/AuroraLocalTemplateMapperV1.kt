package no.skatteetaten.aurora.boober.mapper.v1

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraLocalTemplate

class AuroraLocalTemplateMapperV1(val applicationFiles: List<AuroraConfigFile>, val auroraConfig: AuroraConfig) {

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

    fun findParameters(): List<AuroraConfigFieldHandler> {

        val parameterKeys = applicationFiles.findSubKeys("parameters")

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
        val templateFile = auroraConfigFields.extract<String>("templateFile").let { fileName ->
            auroraConfig.auroraConfigFiles.find { it.name == fileName }?.asJsonNode
        }
        return templateFile ?: throw IllegalArgumentException("templateFile is required")
    }
}