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
    val userDetailsProvider: UserDetailsProvider,
    val openShiftClient: OpenShiftResourceClient,
    val mapper: ObjectMapper
) {

    fun generateObjects(
        template: ObjectNode,
        parameters: Map<String, String>?,
        auroraDeploymentSpec: AuroraDeploymentSpec,
        version: String?,
        replicas: Int?
    ): List<JsonNode> {

        val adcParameters = (parameters ?: emptyMap()).toMutableMap()
        replicas?.let {
            adcParameters.put("REPLICAS", it.toString())
        }
        adcParameters.put("NAME", auroraDeploymentSpec.name)
        val adcParameterKeys = adcParameters.keys

        if (template.has("parameters")) {
            val parameters = template["parameters"]

            // mutation in progress. stay away.
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
            labels.put("affiliation", auroraDeploymentSpec.environment.affiliation)
        }

        if (!labels.has("app")) {
            labels.put("app", auroraDeploymentSpec.name)
        }

        labels.put("updatedBy", userDetailsProvider.getAuthenticatedUser().username.replace(":", "-"))

        if (version != null) {
            template["parameters"]
                .filter { it["name"].asText() == "VERSION" }
                .map {
                    (it as ObjectNode).put("value", version)
                    labels.put("updateInBoober", "true")
                }
        }

        val result = openShiftClient.post("processedtemplate", namespace = auroraDeploymentSpec.environment.namespace, payload = template)

        return result.body["objects"].asSequence().toList()
    }

    fun validateTemplateParameters(templateJson: JsonNode, parameters: Map<String, String>): List<String> {

        val templateParameters = templateJson[PARAMETERS_ATTRIBUTE] as ArrayNode

        val templateParameterNames = templateParameters.map { it[NAME_ATTRIBUTE].textValue() }.toSet()

        val requiredMissingParameters = templateParameters.filter {

            val isRequiredParameter = getBoolean(it, REQUIRED_ATTRIBUTE)
            val noDefaultValueSpecified = it[VALUE_ATTRIBUTE] == null
            isRequiredParameter && noDefaultValueSpecified
        }.map {
            it[NAME_ATTRIBUTE].textValue()
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
        private val NAME_ATTRIBUTE = "name"

        private val REQUIRED_ATTRIBUTE = "required"

        private val VALUE_ATTRIBUTE = "value"

        private val PARAMETERS_ATTRIBUTE = "parameters"

        private fun getBoolean(it: JsonNode, nodeName: String): Boolean {
            val valueNode = it[nodeName]
            return when (valueNode) {
                is BooleanNode -> valueNode.booleanValue()
                is TextNode -> valueNode.textValue() == "true"
                else -> false
            }
        }
    }
}