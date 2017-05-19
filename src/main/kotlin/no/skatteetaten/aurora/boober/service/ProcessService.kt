package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import no.skatteetaten.aurora.boober.model.AuroraProcessConfig
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import org.springframework.stereotype.Service

@Service
class ProcessService(
        val openShiftClient: OpenShiftResourceClient,
        val mapper: ObjectMapper) {


    fun generateObjects(apc: AuroraProcessConfig): List<JsonNode> {

        val template: ObjectNode = if (apc.template != null) {
            openShiftClient.get("template", "openshift", apc.template)?.body as ObjectNode
        } else if (apc.templateFile != null) {
            apc.templateFile as ObjectNode
        } else {
            throw IllegalArgumentException("Template or templateFile should be specified")
        }

        val adcParameters = apc.parameters ?: emptyMap()
        val adcParameterKeys = adcParameters.keys

        val parameters = template["parameters"]

        //mutation in progress. stay away.
        parameters
                .filter { adcParameterKeys.contains(it["name"].textValue()) }
                .forEach {
                    val node = it as ObjectNode
                    node.put("value", adcParameters[it["name"].textValue()] as String)
                }


        if (!template.has("labels")) {
            template.replace("labels", mapper.createObjectNode())
        }

        val labels = template["labels"] as ObjectNode

        if (!labels.has("affiliation")) {
            labels.put("affiliation", apc.affiliation)
        }

        val result = openShiftClient.post("processedtemplate", namespace = apc.namespace, payload = template)

        return result.body["objects"].asSequence().toList()
    }
}