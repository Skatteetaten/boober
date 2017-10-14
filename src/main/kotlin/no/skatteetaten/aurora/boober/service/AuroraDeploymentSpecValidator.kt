package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import org.springframework.stereotype.Service

@Service
class AuroraDeploymentSpecValidator(val openShiftClient: OpenShiftClient) {

    fun assertIsValid(deploymentSpec: AuroraDeploymentSpec) {

        validateAdminGroups(deploymentSpec)
    }

    private fun validateAdminGroups(deploymentSpec: AuroraDeploymentSpec) {
        val adminGroups: Set<String> = deploymentSpec.permissions.admin.groups ?: setOf()
        adminGroups.takeIf { it.isEmpty() }
                ?.let { throw AuroraDeploymentSpecValidationException("permissions.admin.groups cannot be empty") }

        adminGroups.filter { !openShiftClient.isValidGroup(it) }
                .takeIf { it.isNotEmpty() }
                ?.let { throw AuroraDeploymentSpecValidationException("$it is not a valid group") }
    }
}