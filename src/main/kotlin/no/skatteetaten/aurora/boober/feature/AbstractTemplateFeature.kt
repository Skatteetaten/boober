package no.skatteetaten.aurora.boober.feature

import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.text.StringSubstitutor
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.fabric8.kubernetes.api.model.HasMetadata
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.findSubKeys
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeployment
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecValidationException
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.filterNullValues
import no.skatteetaten.aurora.boober.utils.getBoolean
import no.skatteetaten.aurora.boober.utils.normalizeLabels
import no.skatteetaten.aurora.boober.utils.openshiftName

const val TEMPLATE_CONTEXT_KEY = "template"

val FeatureContext.template: JsonNode get() = this.getContextKey(TEMPLATE_CONTEXT_KEY)

abstract class AbstractTemplateFeature(
    val cluster: String
) : Feature {

    abstract fun templateHandlers(
        files: List<AuroraConfigFile>,
        auroraConfig: AuroraConfig
    ): Set<AuroraConfigFieldHandler>

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {

        fun findParameters(): Set<AuroraConfigFieldHandler> {

            val parameterKeys = cmd.applicationFiles.findSubKeys("parameters")

            return parameterKeys.map { parameter ->
                AuroraConfigFieldHandler("parameters/$parameter")
            }.toSet()
        }
        return findParameters() + templateHandlers(cmd.applicationFiles, cmd.auroraConfig) + setOf(
            header.versionHandler,
            AuroraConfigFieldHandler("replicas"),
            AuroraConfigFieldHandler("splunkIndex"),
            AuroraConfigFieldHandler("prometheus/path"),
            AuroraConfigFieldHandler("prometheus/port"),
            // TODO: Part har konfigurert applikasjonene sine slik at de har prometheus/path i about filen.
            AuroraConfigFieldHandler("deployStrategy/type"),
            AuroraConfigFieldHandler("deployStrategy/timeout")
            // TODO: Sirius har deployStrategy/type for templates
        )
    }

    override fun modify(
        adc: AuroraDeploymentSpec,
        resources: Set<AuroraResource>,
        context: FeatureContext
    ) {
        val type = adc.type
        val template = context.template
        val name = template.openshiftName
        val id = DigestUtils.sha1Hex("${type.name.toLowerCase()}-$name")
        resources.forEach {
            if (it.resource.kind == "ApplicationDeployment") {
                val labels = mapOf("applicationId" to id).normalizeLabels()
                modifyResource(it, "Added application name and id")
                val ad: ApplicationDeployment = it.resource as ApplicationDeployment
                ad.spec.runnableType = "DeploymentConfig" // TODO: This might need to change?
                ad.spec.applicationName = name
                ad.metadata.labels = ad.metadata.labels?.addIfNotNull(labels) ?: labels
                ad.spec.applicationId = id
            }
        }
    }

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        context: FeatureContext
    ): List<Exception> {

        if (!fullValidation || adc.cluster != cluster) {
            return emptyList()
        }

        val templateJson = context.template

        val errorMessages = validateTemplateParameters(
            templateJson,
            adc.getParameters().filterNullValues(),
            findParametersFromAuroraConfig(adc)
        )
        if (errorMessages.isNotEmpty()) {
            val message = errorMessages.joinToString(" ").trim()
            return listOf(AuroraDeploymentSpecValidationException(message))
        }

        return emptyList()
    }

    override fun generate(adc: AuroraDeploymentSpec, context: FeatureContext): Set<AuroraResource> {

        val parameters = findParametersFromAuroraConfig(adc) + adc.getParameters().filterNullValues()

        val templateJson = context.template
        val templateResult = processTemplate(templateJson, parameters)

        return templateResult.map {
            val resource: HasMetadata = jacksonObjectMapper().convertValue(it)
            resource.metadata.namespace = adc.namespace
            generateResource(resource)
        }.toSet()
    }

    fun findParametersFromAuroraConfig(adc: AuroraDeploymentSpec): Map<String, String> {
        return mapOf(
            "SPLUNK_INDEX" to adc.splunkIndex,
            "VERSION" to adc.getOrNull<String>("version"),
            "REPLICAS" to adc.getOrNull<String>("replicas"),
            "NAME" to adc.name,
            "CLUSTER" to cluster
        ).filterNullValues()
    }

    /*
     poor mans OpenShift template processor using StringSubstitor
     does not support replacing labels
     does not support generated expressions
     */
    fun processTemplate(templateJson: JsonNode, input: Map<String, String>): Set<JsonNode> {
        val mapper = jacksonObjectMapper()

        val parameters = templateJson.at("/parameters")

        val valueParameters: Map<String, String> =
            parameters.associate {
                val name: String = it["name"].asText()
                val value: String = it["value"]?.asText() ?: ""
                name to value
            }

        val replacer: StringSubstitutor = StringSubstitutor(valueParameters + input, "\${", "}")
        val replacedText = replacer.replace(mapper.writeValueAsString(templateJson))

        val result: JsonNode = mapper.readTree(replacedText)
        return result.at("/objects").toSet()
    }

    fun validateTemplateParameters(
        templateJson: JsonNode,
        parameters: Map<String, String>,
        optionalParameters: Map<String, String>
    ): List<String> {

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
        }.filter {
            !optionalParameters.containsKey(it)
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
