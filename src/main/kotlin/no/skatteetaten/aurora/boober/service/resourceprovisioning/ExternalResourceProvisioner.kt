package no.skatteetaten.aurora.boober.service.resourceprovisioning

import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.*
import org.springframework.stereotype.Service

class ProvisioningResult(
        val schemaProvisionResults: SchemaProvisionResults?,
        val vaultResults: VaultResults?
)

@Service
class ExternalResourceProvisioner(
        val databaseSchemaProvisioner: DatabaseSchemaProvisioner,
        val vaultProvider: VaultProvider
) {

    fun provisionResources(deploymentSpec: AuroraDeploymentSpec): ProvisioningResult {

        val schemaProvisionResult = handleSchemaProvisioning(deploymentSpec)
        val schemaResults = handleVaults(deploymentSpec)
        return ProvisioningResult(schemaProvisionResult, schemaResults)
    }

    private fun handleSchemaProvisioning(deploymentSpec: AuroraDeploymentSpec): SchemaProvisionResults? {
        val schemaProvisionRequests = createSchemaProvisionRequestsFromDeploymentSpec(deploymentSpec)
        if (schemaProvisionRequests.isEmpty()) {
            return null
        }

        return databaseSchemaProvisioner.provisionSchemas(schemaProvisionRequests)
    }

    private fun handleVaults(deploymentSpec: AuroraDeploymentSpec): VaultResults? {

        val vaultRequests = createVaultRequests(deploymentSpec)
        return vaultProvider.findVaultData(vaultRequests)
    }

    companion object {
        @JvmStatic
        protected fun createSchemaProvisionRequestsFromDeploymentSpec(deploymentSpec: AuroraDeploymentSpec): List<SchemaProvisionRequest> {
            val databaseSpecs = deploymentSpec.deploy?.database ?: listOf()
            return databaseSpecs.map {
                val name = it.name.toLowerCase()
                if (it.id != null) {
                    SchemaIdRequest(it.id, name)
                } else {
                    SchemaForAppRequest(deploymentSpec.environment.affiliation, deploymentSpec.environment.envName, deploymentSpec.name, name)
                }
            }
        }

        @JvmStatic
        protected fun createVaultRequests(deploymentSpec: AuroraDeploymentSpec): List<VaultRequest> {
            val volume = deploymentSpec.volume ?: return listOf()

            val secretVaultNames = volume.mounts?.mapNotNull { it.secretVaultName }.orEmpty()
            val secretVaultKeys = volume.secretVaultKeys?.mapNotNull { it }.orEmpty()
            val allVaultNames = volume.secretVaultName?.let { secretVaultNames + listOf(it) } ?: secretVaultNames

            return allVaultNames.map { VaultRequest(deploymentSpec.environment.affiliation, it, secretVaultKeys) }
        }
    }
}