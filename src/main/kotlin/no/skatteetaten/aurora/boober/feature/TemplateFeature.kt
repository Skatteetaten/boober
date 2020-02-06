package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecValidationException
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class TemplateFeature(
    val openShiftClient: OpenShiftClient,
    @Value("\${openshift.cluster}") c: String
) : AbstractTemplateFeature(c) {
    override fun enable(header: AuroraDeploymentSpec) = header.type == TemplateType.template && header.deployState != DeploymentState.deployment

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

    override fun findTemplate(adc: AuroraDeploymentSpec, cmd: AuroraContextCommand): JsonNode {
        val templateName: String = adc["template"]
        return try {
            openShiftClient.getTemplate(templateName)
        } catch (e: Exception) {
            throw AuroraDeploymentSpecValidationException("Could not find template=$templateName")
        }
    }
}
