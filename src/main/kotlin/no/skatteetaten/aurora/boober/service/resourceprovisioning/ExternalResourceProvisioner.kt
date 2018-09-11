package no.skatteetaten.aurora.boober.service.resourceprovisioning

import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpecInternal
import org.springframework.stereotype.Service

class ProvisioningResult(
    val schemaProvisionResults: SchemaProvisionResults?,
    val vaultResults: VaultResults?,
    val stsProvisioningResult: StsProvisioningResult?
)

@Service
class ExternalResourceProvisioner(
    val databaseSchemaProvisioner: DatabaseSchemaProvisioner,
    val stsProvisioner: StsProvisioner,
    val vaultProvider: VaultProvider
) {

    fun provisionResources(deploymentSpecInternal: AuroraDeploymentSpecInternal): ProvisioningResult {

        val stsProvisioningResult = handleSts(deploymentSpec)
        val schemaProvisionResult = handleSchemaProvisioning(deploymentSpecInternal)
        val schemaResults = handleVaults(deploymentSpecInternal)
        return ProvisioningResult(schemaProvisionResult, schemaResults, stsProvisioningResult)
    }

    private fun handleSts(deploymentSpec: AuroraDeploymentSpec): StsProvisioningResult? {
        return deploymentSpec.integration?.certificate?.let {
            stsProvisioner.generateCertificate(it, deploymentSpec.name, deploymentSpec.environment.envName)
        }
    }

    private fun handleSchemaProvisioning(deploymentSpecInternal: AuroraDeploymentSpecInternal): SchemaProvisionResults? {
        val schemaProvisionRequests = createSchemaProvisionRequestsFromDeploymentSpec(deploymentSpecInternal)
        if (schemaProvisionRequests.isEmpty()) {
            return null
        }

        return databaseSchemaProvisioner.provisionSchemas(schemaProvisionRequests)
    }

    private fun handleVaults(deploymentSpecInternal: AuroraDeploymentSpecInternal): VaultResults? {

        val vaultRequests = createVaultRequests(deploymentSpecInternal)
        return vaultProvider.findVaultData(vaultRequests)
    }

    companion object {
        @JvmStatic
        protected fun createSchemaProvisionRequestsFromDeploymentSpec(deploymentSpecInternal: AuroraDeploymentSpecInternal): List<SchemaProvisionRequest> {
            val databaseSpecs = deploymentSpecInternal.integration?.database ?: listOf()
            return databaseSpecs.map {
                val name = it.name.toLowerCase()
                if (it.id != null) {
                    SchemaIdRequest(it.id, name)
                } else {
                    SchemaForAppRequest(
                        deploymentSpecInternal.environment.affiliation,
                        deploymentSpecInternal.environment.envName,
                        deploymentSpecInternal.name,
                        name
                    )
                }
            }
        }

        @JvmStatic
        protected fun createVaultRequests(deploymentSpecInternal: AuroraDeploymentSpecInternal): List<VaultRequest> {
            val volume = deploymentSpecInternal.volume ?: return listOf()

            val secretVaultNames = volume.mounts?.mapNotNull { it.secretVaultName }.orEmpty()
            val allVaultNames = volume.secretVaultName?.let { secretVaultNames + listOf(it) } ?: secretVaultNames

            return allVaultNames.map {
                VaultRequest(
                    deploymentSpecInternal.environment.affiliation,
                    it,
                    volume.secretVaultKeys,
                    volume.keyMappings
                )
            }
        }
    }
}