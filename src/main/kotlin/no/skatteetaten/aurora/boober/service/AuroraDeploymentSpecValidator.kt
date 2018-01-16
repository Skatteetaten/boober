package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseSchemaProvisioner
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class AuroraDeploymentSpecValidator(
        val openShiftClient: OpenShiftClient,
        val openShiftTemplateProcessor: OpenShiftTemplateProcessor,
        val databaseSchemaProvisioner: DatabaseSchemaProvisioner) {


    val logger: Logger = LoggerFactory.getLogger(AuroraDeploymentSpecValidator::class.java)

    @Throws(AuroraDeploymentSpecValidationException::class)
    fun assertIsValid(deploymentSpec: AuroraDeploymentSpec) {

        validateAdminGroups(deploymentSpec)
        validateTemplateIfSet(deploymentSpec)
        validateDatatbaseId(deploymentSpec)
    }

    private fun validateDatatbaseId(deploymentSpec: AuroraDeploymentSpec) {
        deploymentSpec.deploy?.database
                ?.forEach {
                    it.id?.let {
                        try {
                            databaseSchemaProvisioner.findSchemaById(it)
                        } catch (e: Exception) {
                            throw AuroraDeploymentSpecValidationException("Database schema with id=$it does not exist")
                        }
                    }
                }
    }

    private fun validateAdminGroups(deploymentSpec: AuroraDeploymentSpec) {

        val adminGroups: Set<String> = deploymentSpec.environment.permissions.admin.groups ?: setOf()
        adminGroups.takeIf { it.isEmpty() }
                ?.let { throw AuroraDeploymentSpecValidationException("permissions.admin.groups cannot be empty") }

        val groupUsers = openShiftClient.getGroups().groupUsers
        adminGroups.filter { !groupUsers.containsKey(it) }
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