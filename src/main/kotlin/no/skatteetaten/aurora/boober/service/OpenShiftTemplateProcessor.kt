package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.BooleanNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import org.springframework.stereotype.Service

@Service
class OpenShiftTemplateProcessor(
        val openShiftClient: OpenShiftResourceClient,
        val mapper: ObjectMapper) {


    fun generateObjects(template: ObjectNode, parameters: Map<String, String>?, aac: AuroraDeploymentSpec): List<JsonNode> {

        val adcParameters = parameters ?: emptyMap()
        val adcParameterKeys = adcParameters.keys

        if (template.has("parameters")) {
            val parameters = template["parameters"]

            //mutation in progress. stay away.
            parameters
                    .filter { adcParameterKeys.contains(it["name"].textValue()) }
                    .forEach {
                        val node = it as ObjectNode
                        node.put("value", adcParameters[it["name"].textValue()] as String)
                    }
        }

        if (!template.has("labels")) {
            template.replace("labels", mapper.createObjectNode())
        }

        val labels = template["labels"] as ObjectNode

        if (!labels.has("affiliation")) {
            labels.put("affiliation", aac.affiliation)
        }

        if (!labels.has("app")) {
            labels.put("app", aac.name)
        }

        //TODO: we should not use aoc-validate here. Please fix. 
        val result = openShiftClient.post("processedtemplate", namespace = "aoc-validate", payload = template)

        return result.body["objects"].asSequence().toList()
    }

    fun validateTemplateParameters(templateJson: JsonNode, parameters: Map<String, String>) {

        val templateParameters = templateJson["parameters"] as ArrayNode

        val templateParameterNames = templateParameters.map { it["name"].textValue() }.toSet()

        val requiredMissingParameters = templateParameters.filter {
            val requiredNode = it["required"]
            when (requiredNode) {
                is BooleanNode -> requiredNode.booleanValue()
                is TextNode -> requiredNode.textValue() == "true"
                else -> false
            }
        }.map {
            it["name"].textValue()
        }.filter {
            !parameters.containsKey(it)
        }

        val notMappedParameterNames = parameters.keys - templateParameterNames


        if (requiredMissingParameters.isEmpty() && notMappedParameterNames.isEmpty()) {
            return
        }

        val missingParameterString: String = requiredMissingParameters.takeIf { !it.isEmpty() }?.let {
            val parametersString = it.joinToString(", ")
            "Required template parameters [${parametersString}] not set."
        } ?: ""

        val tooManyParametersString: String = notMappedParameterNames.takeIf { !it.isEmpty() }?.let {
            val parametersString = it.joinToString(", ")
            "Template does not contain parameter(s) [${parametersString}]."
        } ?: ""

        val errorMessage = listOf(missingParameterString, tooManyParametersString).joinToString(" ")

        throw AuroraDeploymentSpecValidationException(errorMessage.trim())

    }
}