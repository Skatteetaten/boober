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
        @JvmStatic
        protected fun createSchemaProvisionRequestsFromDeploymentSpec(deploymentSpec: AuroraDeploymentSpec): List<SchemaProvisionRequest> {
            val databaseSpecs = deploymentSpec.deploy?.database ?: listOf()
            return databaseSpecs.map {
                if (it.id != null) {
                    SchemaIdRequest(it.id, it.name)
                } else {
                    SchemaForAppRequest(deploymentSpec.affiliation, deploymentSpec.envName, deploymentSpec.name, it.name)
                }
            }
        }
    }
}