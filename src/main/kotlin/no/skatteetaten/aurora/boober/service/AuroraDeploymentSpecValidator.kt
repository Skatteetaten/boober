package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class AuroraDeploymentSpecValidator(val openShiftClient: OpenShiftClient, val openShiftTemplateProcessor: OpenShiftTemplateProcessor) {


    val logger: Logger = LoggerFactory.getLogger(AuroraDeploymentSpecValidator::class.java)

    @Throws(AuroraDeploymentSpecValidationException::class)
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
                ?.let { it: List<String> -> throw AuroraDeploymentSpecValidationException("$it is not a valid group") }
    }

    private fun validateTemplateIfSet(deploymentSpec: AuroraDeploymentSpec) {

        deploymentSpec.localTemplate?.let {
            openShiftTemplateProcessor.validateTemplateParameters(it.templateJson, it.parameters ?: emptyMap())
                    .takeIf { it.isNotEmpty() }
                    ?.let { throw AuroraDeploymentSpecValidationException(it.joinToString(". ").trim()) }
        }

        deploymentSpec.template?.let {
            val templateJson = openShiftClient.getTemplate(it.template) ?: throw AuroraDeploymentSpecValidationException("Template ${it.template} does not exist")
            openShiftTemplateProcessor.validateTemplateParameters(templateJson, it.parameters ?: emptyMap())
                    .takeIf { it.isNotEmpty() }
                    ?.let { throw AuroraDeploymentSpecValidationException(it.joinToString(". ").trim()) }
        }
    }
}