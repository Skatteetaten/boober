package no.skatteetaten.aurora.boober.feature

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec

@Service
class LocalTemplateFeature(
    @Value("\${openshift.cluster}") c: String
) : AbstractTemplateFeature(c) {

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

    override fun createContext(
        spec: AuroraDeploymentSpec,
        cmd: AuroraContextCommand,
        validationContext: Boolean
    ): Map<String, Any> {
        val templateFile = spec.get<String>("templateFile").let { fileName ->
            cmd.auroraConfig.files.find { it.name == fileName }?.asJsonNode
        }
        return templateFile?.let {
            mapOf("template" to it)
        } ?: throw IllegalArgumentException("templateFile is required")
    }
}
