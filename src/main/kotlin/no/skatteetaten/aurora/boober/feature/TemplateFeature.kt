package no.skatteetaten.aurora.boober.feature

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.AuroraTemplateService

@Service
class TemplateFeature(
    val templateService: AuroraTemplateService,
    @Value("\${openshift.cluster}") c: String
) : AbstractTemplateFeature(c) {
    override fun enable(header: AuroraDeploymentSpec) = header.type == TemplateType.template

    override fun templateHandlers(
        files: List<AuroraConfigFile>,
        auroraConfig: AuroraConfig
    ): Set<AuroraConfigFieldHandler> {
        return setOf(
            AuroraConfigFieldHandler("template", validator = { json ->
                val template = json?.textValue()
                if (template == null) {
                    IllegalArgumentException("Template is required")
                } else {
                    null
                }
            })
        )
    }

    override fun createContext(
        spec: AuroraDeploymentSpec,
        cmd: AuroraContextCommand,
        validationContext: Boolean
    ): Map<String, Any> {
        if (validationContext) {
            return mapOf()
        }
        return mapOf(TEMPLATE_CONTEXT_KEY to templateService.findTemplate(spec["template"]))
    }
}
