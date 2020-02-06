package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class LocalTemplateFeature(
    @Value("\${openshift.cluster}") c: String
) : AbstractTemplateFeature(c) {

    override fun enable(header: AuroraDeploymentSpec) = header.type == TemplateType.localTemplate && header.deployState != DeploymentState.deployment

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
