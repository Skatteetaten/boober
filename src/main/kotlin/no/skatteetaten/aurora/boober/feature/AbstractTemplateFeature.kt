package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.fabric8.kubernetes.api.model.HasMetadata
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentContext
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.mapper.v1.findSubKeys
import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.service.AuroraResource
import no.skatteetaten.aurora.boober.service.Feature
import no.skatteetaten.aurora.boober.utils.filterNullValues
import no.skatteetaten.aurora.boober.utils.pattern
import org.apache.commons.text.StringSubstitutor


val templateVersion = AuroraConfigFieldHandler("version", validator = {
    it.pattern(
            "^[\\w][\\w.-]{0,127}$",
            "Version must be a 128 characters or less, alphanumeric and can contain dots and dashes",
            false
    )
})

abstract class AbstractTemplateFeature() : Feature {


    abstract fun templateHandlers(files: List<AuroraConfigFile>, auroraConfig: AuroraConfig): Set<AuroraConfigFieldHandler>

    abstract fun findTemplate(adc: AuroraDeploymentContext): JsonNode


    override fun handlers(header: AuroraDeploymentContext): Set<AuroraConfigFieldHandler> {

        fun findParameters(): Set<AuroraConfigFieldHandler> {

            val parameterKeys = header.applicationFiles.findSubKeys("parameters")

            return parameterKeys.map { parameter ->
                AuroraConfigFieldHandler("parameters/$parameter")
            }.toSet()
        }
        return findParameters() + templateHandlers(header.applicationFiles, header.auroraConfig) + setOf(
                templateVersion,
                AuroraConfigFieldHandler("replicas"),
                AuroraConfigFieldHandler("splunkIndex")
        )
    }

    override fun generate(adc: AuroraDeploymentContext): Set<AuroraResource> {

        val parameters = mapOf(
                "SPLUNK_INDEX" to adc.getOrNull<String>("splunkIndex"),
                "VERSION" to adc.getOrNull<String>("version"),
                "REPLICAS" to adc.getOrNull<String>("replicas"),
                "NAME" to adc.name
        ) + adc.getParameters()

        val templateJson = findTemplate(adc)
        val templateResult = processTemplate(templateJson, parameters.filterNullValues())

        return templateResult.map {
            val resource: HasMetadata = jacksonObjectMapper().convertValue(it)
            resource.metadata.namespace=adc.namespace
            AuroraResource("${resource.metadata.name}-${resource.kind}", resource)
        }.toSet()
    }

    /*
     poor mans OpenShift template processor using StringSubstitor
     does not support replacing labels
     does not support generated expressions
     */
    fun processTemplate(templateJson: JsonNode, input: Map<String, String>): Set<JsonNode> {
        val mapper = jacksonObjectMapper()

        val parameters = templateJson.at("/parameters")

        val valueParameters: Map<String, String> = parameters.filter { it["value"] != null }.associate { it["name"].asText() to it["value"].asText() }

        val replacer: StringSubstitutor = StringSubstitutor(valueParameters + input, "\${", "}")
        val replacedText = replacer.replace(mapper.writeValueAsString(templateJson))

        val result: JsonNode = mapper.readTree(replacedText)
        return result.at("/objects").toSet()
    }
}