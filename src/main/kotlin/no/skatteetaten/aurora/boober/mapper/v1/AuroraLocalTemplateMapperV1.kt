package no.skatteetaten.aurora.boober.mapper.v1

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraLocalTemplate

class AuroraLocalTemplateMapperV1(val applicationFiles: List<AuroraConfigFile>, val auroraConfig: AuroraConfig) {

    val parameterHandlers = findParameters()
    val handlers = parameterHandlers + listOf(
        AuroraConfigFieldHandler("templateFile", validator = { json ->
            val fileName = json?.textValue()
            if (auroraConfig.files.none { it.name == fileName }) {
                IllegalArgumentException("The file named $fileName does not exist in AuroraConfig")
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

    fun localTemplate(auroraDeploymentSpec: AuroraDeploymentSpec): AuroraLocalTemplate {
        return AuroraLocalTemplate(
            parameters = auroraDeploymentSpec.getParameters(parameterHandlers),
            templateJson = extractTemplateJson(auroraDeploymentSpec),
            version = auroraDeploymentSpec.extractOrNull("version"),
            replicas = auroraDeploymentSpec.extractOrNull("replicas")
        )
    }

    private fun extractTemplateJson(auroraDeploymentSpec: AuroraDeploymentSpec): JsonNode {
        val templateFile = auroraDeploymentSpec.get<String>("templateFile").let { fileName ->
            auroraConfig.files.find { it.name == fileName }?.asJsonNode
        }
        return templateFile ?: throw IllegalArgumentException("templateFile is required")
    }
}