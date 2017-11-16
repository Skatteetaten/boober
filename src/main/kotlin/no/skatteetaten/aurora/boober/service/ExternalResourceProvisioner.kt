package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import org.springframework.stereotype.Service

class ProvisioningResult(val schemaProvisionResults: SchemaProvisionResults?)

@Service
class ExternalResourceProvisioner(val databaseSchemaProvisioner: DatabaseSchemaProvisioner) {
    fun provisionResources(deploymentSpec: AuroraDeploymentSpec): ProvisioningResult {

        val schemaProvisionResult = handleSchemaProvisioning(deploymentSpec)
        return ProvisioningResult(schemaProvisionResult)
    }

    private fun handleSchemaProvisioning(deploymentSpec: AuroraDeploymentSpec): SchemaProvisionResults? {
        val schemaProvisionRequests = createSchemaProvisionRequestsFromDeploymentSpec(deploymentSpec)
        if (schemaProvisionRequests.isEmpty()) {
            return null
        }
        return databaseSchemaProvisioner.provisionSchemas(schemaProvisionRequests)
    }

    companion object {
        private fun createSchemaProvisionRequestsFromDeploymentSpec(deploymentSpec: AuroraDeploymentSpec): List<SchemaProvisionRequest> {
            return deploymentSpec.deploy?.database
                    ?.map { SchemaIdRequest("fd59dba9-7d67-4ea2-bb98-081a5df8c387") }
                    .orEmpty()
        }
    }
}