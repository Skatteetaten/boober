package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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
            openShiftClient.findTemplate(deployDescriptor.template)?.let { it.body } ?:
                    throw IllegalArgumentException("Template with name ${deployDescriptor.template} does not exist")
            //find the template in openshift namespace
        } else if (deployDescriptor.templateFile != null) {

            mapper.(deployDescriptor.templateFile)
        }

    }
}