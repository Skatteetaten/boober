package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentContext
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpecInternal
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecValidationException
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import org.springframework.stereotype.Service

@Service
class TemplateFeature(val openShiftClient: OpenShiftClient) : AbstractTemplateFeature() {
    override fun enable(header: AuroraDeploymentContext) = header.type == TemplateType.template

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




    override fun findTemplate(adc: AuroraDeploymentContext): JsonNode {
        return openShiftClient.getTemplate(adc["template"])
    }
}