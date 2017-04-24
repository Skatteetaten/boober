package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.convertValue
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfig
import no.skatteetaten.aurora.boober.model.TemplateDeploy
import org.springframework.stereotype.Service

@Service
class ProcessService(
        val openShiftClient: OpenShiftClient,
        val mapper: ObjectMapper) {


    fun generateObjects(adc: AuroraDeploymentConfig): List<JsonNode> {

        val deployDescriptor = adc.deployDescriptor as TemplateDeploy

        val template: JsonNode = if (deployDescriptor.template != null) {
            openShiftClient.findTemplate(deployDescriptor.template)?.body ?:
                    throw IllegalArgumentException("Template with name ${deployDescriptor.template} does not exist")
        } else if (deployDescriptor.templateFile != null) {
            mapper.convertValue<JsonNode>(deployDescriptor.templateFile)
        } else {
            throw IllegalArgumentException("Template or templateFile should be specified")
        }


        //need to set params from adc into template

        //need to process template and return list of items

    }
}