package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
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

        deploymentSpec.type.takeIf { it == TemplateType.template } ?: return

        deploymentSpec.template
                ?.takeIf { !openShiftClient.templateExist(it.template) }
                ?.let { throw AuroraDeploymentSpecValidationException("Template ${it.template} does not exist") }
    }
}