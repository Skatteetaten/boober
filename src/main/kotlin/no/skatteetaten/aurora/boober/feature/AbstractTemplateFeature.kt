package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.fabric8.kubernetes.api.model.HasMetadata
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentCommand
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.mapper.findSubKeys
import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeployment
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecValidationException
import no.skatteetaten.aurora.boober.service.AuroraResource
import no.skatteetaten.aurora.boober.service.Feature
import no.skatteetaten.aurora.boober.utils.filterNullValues
import no.skatteetaten.aurora.boober.utils.getBoolean
import no.skatteetaten.aurora.boober.utils.openshiftName
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.text.StringSubstitutor


abstract class AbstractTemplateFeature() : Feature {


    abstract fun templateHandlers(files: List<AuroraConfigFile>, auroraConfig: AuroraConfig): Set<AuroraConfigFieldHandler>

    abstract fun findTemplate(adc: AuroraDeploymentSpec, cmd: AuroraDeploymentCommand): JsonNode


    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraDeploymentCommand): Set<AuroraConfigFieldHandler> {

        fun findParameters(): Set<AuroraConfigFieldHandler> {

            val parameterKeys = cmd.applicationFiles.findSubKeys("parameters")

            return parameterKeys.map { parameter ->
                AuroraConfigFieldHandler("parameters/$parameter")
            }.toSet()
        }
        return findParameters() + templateHandlers(cmd.applicationFiles, cmd.auroraConfig) + setOf(
                header.versionHandler,
                AuroraConfigFieldHandler("replicas"),
                AuroraConfigFieldHandler("splunkIndex")
        )
    }

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, cmd: AuroraDeploymentCommand) {
        val type = adc.type
        val template = findTemplate(adc, cmd)
        val name = template.openshiftName
        val id = DigestUtils.sha1Hex("${type.name.toLowerCase()}-$name")
        resources.forEach {
            if (it.resource.kind == "ApplicationDeployment") {
                val ad: ApplicationDeployment = jacksonObjectMapper().convertValue(it.resource)
                ad.spec.applicationName = name
                ad.spec.applicationId = id
            }
        }
    }

    override fun validate(adc: AuroraDeploymentSpec, fullValidation: Boolean, cmd: AuroraDeploymentCommand): List<Exception> {

        val templateJson = try {
            findTemplate(adc, cmd)
        } catch (e: Exception) {
            return listOf(AuroraDeploymentSpecValidationException("Could not find template", e))
        }

        val errorMessages = validateTemplateParameters(templateJson, findParameters(adc))
        if (errorMessages.isNotEmpty()) {
            return listOf(AuroraDeploymentSpecValidationException(errorMessages.joinToString { " " }.trim()))
        }

        return emptyList()

    }

    override fun generate(adc: AuroraDeploymentSpec, cmd: AuroraDeploymentCommand): Set<AuroraResource> {

        val parameters = findParameters(adc)

        val templateJson = findTemplate(adc, cmd)
        val templateResult = processTemplate(templateJson, parameters)

        return templateResult.map {
            val resource: HasMetadata = jacksonObjectMapper().convertValue(it)
            resource.metadata.namespace = adc.namespace
            AuroraResource("${resource.metadata.name}-${resource.kind}", resource)
        }.toSet()
    }

    fun findParameters(adc: AuroraDeploymentSpec): Map<String, String> {
        val parameters = mapOf(
                "SPLUNK_INDEX" to adc.splunkIndex,
                "VERSION" to adc.getOrNull<String>("version"),
                "REPLICAS" to adc.getOrNull<String>("replicas"),
                "NAME" to adc.name
        ) + adc.getParameters()
        return parameters.filterNullValues()
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

    fun validateTemplateParameters(templateJson: JsonNode, parameters: Map<String, String>): List<String> {

        val templateParameters = templateJson[PARAMETERS_ATTRIBUTE] as ArrayNode

        val templateParameterNames = templateParameters.map { it[NAME_ATTRIBUTE].textValue() }.toSet()

        val requiredMissingParameters = templateParameters.filter {

            val isRequiredParameter = it.getBoolean(REQUIRED_ATTRIBUTE)
            val noDefaultValueSpecified = it[VALUE_ATTRIBUTE] == null

            isRequiredParameter && noDefaultValueSpecified
        }.map {
            it[NAME_ATTRIBUTE].textValue()
        }.filter {
            !populatedParameters.contains(it)
        }.filter {
            !parameters.containsKey(it)
        }

        val notMappedParameterNames = parameters.keys - templateParameterNames

        if (requiredMissingParameters.isEmpty() && notMappedParameterNames.isEmpty()) {
            return listOf()
        }

        val errorMessages = mutableListOf<String>()

        requiredMissingParameters.takeIf { !it.isEmpty() }?.let {
            val parametersString = it.joinToString(", ")
            errorMessages.add("Required template parameters [$parametersString] not set")
        }

        notMappedParameterNames.takeIf { !it.isEmpty() }?.let {
            val parametersString = it.joinToString(", ")
            errorMessages.add("Template does not contain parameter(s) [$parametersString]")
        }

        return errorMessages
    }

    companion object {
        val populatedParameters: Set<String> = setOf("REPLICAS", "SPLUNK_INDEX", "NAME", "VERSION")
        private val NAME_ATTRIBUTE = "name"

        private val REQUIRED_ATTRIBUTE = "required"

        private val VALUE_ATTRIBUTE = "value"

        private val PARAMETERS_ATTRIBUTE = "parameters"

    }
}