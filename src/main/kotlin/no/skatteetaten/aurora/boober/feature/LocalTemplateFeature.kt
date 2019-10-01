package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraContextCommand
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.mapper.TemplateType
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import org.springframework.stereotype.Service

@Service
class LocalTemplateFeature : AbstractTemplateFeature() {

    override fun enable(header: AuroraDeploymentSpec) = header.type == TemplateType.localTemplate

    override fun templateHandlers(
        files: List<AuroraConfigFile>,
        auroraConfig: AuroraConfig
    ): Set<AuroraConfigFieldHandler> {
        return setOf(AuroraConfigFieldHandler("templateFile", validator = { json ->
            val fileName = json?.textValue()
            if (auroraConfig.files.none { it.name == fileName }) {
                IllegalArgumentException("The file named $fileName does not exist in AuroraConfig")
            } else {
                null
            }
        }))
    }

    override fun findTemplate(adc: AuroraDeploymentSpec, cmd: AuroraContextCommand): JsonNode {
        val templateFile = adc.get<String>("templateFile").let { fileName ->
            cmd.auroraConfig.files.find { it.name == fileName }?.asJsonNode
        }
        return templateFile ?: throw IllegalArgumentException("templateFile is required")
    }
}