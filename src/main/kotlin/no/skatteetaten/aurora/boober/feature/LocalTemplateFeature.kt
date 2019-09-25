package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentContext
import no.skatteetaten.aurora.boober.mapper.TemplateType
import no.skatteetaten.aurora.boober.model.*
import org.springframework.stereotype.Service

@Service
class LocalTemplateFeature() : AbstractTemplateFeature() {

    override fun enable(header: AuroraDeploymentContext) = header.type == TemplateType.localTemplate

    override fun templateHandlers(files: List<AuroraConfigFile>, auroraConfig: AuroraConfig): Set<AuroraConfigFieldHandler> {
        return setOf(AuroraConfigFieldHandler("templateFile", validator = { json ->
            val fileName = json?.textValue()
            if (auroraConfig.files.none { it.name == fileName }) {
                IllegalArgumentException("The file named $fileName does not exist in AuroraConfig")
            } else {
                null
            }
        }))
    }

    override fun findTemplate(adc: AuroraDeploymentContext): JsonNode {
        val templateFile = adc.get<String>("templateFile").let { fileName ->
            adc.auroraConfig.files.find { it.name == fileName }?.asJsonNode
        }
        return templateFile ?: throw IllegalArgumentException("templateFile is required")
    }

}