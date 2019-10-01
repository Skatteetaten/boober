package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraContextCommand
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.mapper.TemplateType
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import org.springframework.stereotype.Service

@Service
class TemplateFeature(val openShiftClient: OpenShiftClient) : AbstractTemplateFeature() {
    override fun enable(header: AuroraDeploymentSpec) = header.type == TemplateType.template

    override fun templateHandlers(files: List<AuroraConfigFile>, auroraConfig: AuroraConfig): Set<AuroraConfigFieldHandler> {
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

    override fun findTemplate(adc: AuroraDeploymentSpec, cmd: AuroraContextCommand): JsonNode {
        return openShiftClient.getTemplate(adc["template"])
    }
}