package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.convertValue
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfig
import no.skatteetaten.aurora.boober.model.TemplateDeploy
import org.springframework.stereotype.Service

@Service
class ProcessService(
        val openShiftClient: OpenshiftResourceClient,
        val mapper: ObjectMapper) {


    fun generateObjects(adc: AuroraDeploymentConfig): List<JsonNode> {

        val deployDescriptor = adc.deployDescriptor as TemplateDeploy

        val template: ObjectNode = if (deployDescriptor.template != null) {
            openShiftClient.get("template", "openshift", deployDescriptor.template)?.body as ObjectNode
        } else if (deployDescriptor.templateFile != null) {
            mapper.convertValue<ObjectNode>(deployDescriptor.templateFile)
        } else {
            throw IllegalArgumentException("Template or templateFile should be specified")
        }

        val adcParameters = deployDescriptor.parameters ?: emptyMap()
        val adcParameterKeys = adcParameters.keys

        val parameters = template["parameters"]

        //mutation in progress. stay away.
        parameters
                .filter { adcParameterKeys.contains(it["name"].textValue()) }
                .forEach {
                    val node = it as ObjectNode
                    node.put("value", adcParameters[it["name"].textValue()] as String)
                }
        val result = openShiftClient.post("processedtemplate", namespace = adc.namespace, payload = template)

        return result.body["objects"].asSequence().toList()
    }
}