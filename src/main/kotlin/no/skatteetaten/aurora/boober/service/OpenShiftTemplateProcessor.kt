package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
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
        val result = openShiftClient.post("processedtemplate", namespace = aac.namespace, payload = template)

        return result.body["objects"].asSequence().toList()
    }
}