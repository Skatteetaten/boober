package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.BooleanNode
import com.fasterxml.jackson.databind.node.TextNode
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.model.TemplateType.development
import no.skatteetaten.aurora.boober.model.TemplateType.localTemplate
import no.skatteetaten.aurora.boober.model.TemplateType.template
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import org.apache.velocity.Template
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class AuroraDeploymentSpecValidator(val openShiftClient: OpenShiftClient) {


    val logger: Logger = LoggerFactory.getLogger(AuroraDeploymentSpecValidator::class.java)

    fun assertIsValid(deploymentSpec: AuroraDeploymentSpec) {

        validateAdminGroups(deploymentSpec)
        validateTemplateIfSet(deploymentSpec)
    }

    private fun validateAdminGroups(deploymentSpec: AuroraDeploymentSpec) {

        val adminGroups: Set<String> = deploymentSpec.permissions.admin.groups ?: setOf()
        adminGroups.takeIf { it.isEmpty() }
                ?.let { throw AuroraDeploymentSpecValidationException("permissions.admin.groups cannot be empty") }

        adminGroups.filter { !openShiftClient.isValidGroup(it) }
                .takeIf { it.isNotEmpty() }
                ?.let { throw AuroraDeploymentSpecValidationException("$it is not a valid group") }
    }

    private fun validateTemplateIfSet(deploymentSpec: AuroraDeploymentSpec) {

        deploymentSpec.localTemplate?.let {
            validateTemplateParameters(it.templateJson, it.parameters ?: emptyMap())
        }

        deploymentSpec.template?.let {
            val templateJson = openShiftClient.getTemplate(it.template) ?: throw AuroraDeploymentSpecValidationException("Template ${it.template} does not exist")
            validateTemplateParameters(templateJson, it.parameters ?: emptyMap())
        }

    }


    private fun validateTemplateParameters(templateJson: JsonNode, parameters: Map<String, String>) {

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