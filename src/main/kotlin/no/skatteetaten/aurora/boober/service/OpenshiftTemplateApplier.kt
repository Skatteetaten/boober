package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import no.skatteetaten.aurora.boober.model.AuroraLocalTemplateConfig
import no.skatteetaten.aurora.boober.model.AuroraObjectsConfig
import no.skatteetaten.aurora.boober.model.AuroraProcessConfig
import no.skatteetaten.aurora.boober.model.AuroraTemplateConfig
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import org.springframework.stereotype.Service

@Service
class OpenshiftTemplateApplier(
        val openShiftClient: OpenShiftResourceClient,
        val mapper: ObjectMapper) {


    fun generateObjects(apc: AuroraProcessConfig): List<JsonNode> {


        val template = if (apc is AuroraTemplateConfig) {
            openShiftClient.get("template", "openshift", apc.template)?.body as ObjectNode
        } else if (apc is AuroraLocalTemplateConfig) {
            apc.templateJson as ObjectNode
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

        val base = apc as AuroraObjectsConfig
        if (!labels.has("affiliation")) {
            labels.put("affiliation", base.affiliation)
        }

        val result = openShiftClient.post("processedtemplate", namespace = base.namespace, payload = template)

        return result.body["objects"].asSequence().toList()
    }
}