package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import org.springframework.stereotype.Service

class ProvisioningResult(val schemaProvisionResult: SchemaProvisionResult?)

@Service
class ExternalResourceProvisioner(val databaseSchemaProvisioner: DatabaseSchemaProvisioner) {
    fun provisionResources(deploymentSpec: AuroraDeploymentSpec): ProvisioningResult {

        val schemaProvisionResult = handleSchemaProvisioning(deploymentSpec)
        return ProvisioningResult(schemaProvisionResult)
    }

    private fun handleSchemaProvisioning(deploymentSpec: AuroraDeploymentSpec): SchemaProvisionResult? {
        val schemaProvisionRequests = createSchemaProvisionRequestsFromDeploymentSpec(deploymentSpec)
        if (schemaProvisionRequests.isEmpty()) {
            return null
        }
        return databaseSchemaProvisioner.provisionSchemas(schemaProvisionRequests)
    }

    companion object {
        private fun createSchemaProvisionRequestsFromDeploymentSpec(deploymentSpec: AuroraDeploymentSpec): List<SchemaProvisionRequest> {
            return deploymentSpec.deploy?.database
                    ?.map { SchemaProvisionRequest(deploymentSpec.affiliation) }
                    .orEmpty()
        }
    }
}