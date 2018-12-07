package no.skatteetaten.aurora.boober.service.resourceprovisioning

import no.skatteetaten.aurora.boober.mapper.v1.DatabasePermission
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpecInternal
import no.skatteetaten.aurora.boober.model.Database
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

    fun provisionResources(deploymentSpecInternal: AuroraDeploymentSpecInternal): ProvisioningResult {

        val schemaProvisionResult = handleSchemaProvisioning(deploymentSpecInternal)
        val schemaResults = handleVaults(deploymentSpecInternal)
        return ProvisioningResult(schemaProvisionResult, schemaResults)
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

                val details = it.createSchemaDetails(deploymentSpecInternal.environment.affiliation)
                if (it.id != null) {
                    SchemaIdRequest(
                        id = it.id,
                        details = details
                    )
                } else {
                    SchemaForAppRequest(
                        environment = deploymentSpecInternal.environment.envName,
                        application = deploymentSpecInternal.name,
                        details = details,
                        generate = it.generate
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

fun Database.createSchemaDetails(affiliation: String): SchemaRequestDetails {
    val roles = if (this.roles.isEmpty()) {
        mapOf("SCHEMA" to DatabasePermission.ALL)
    } else this.roles

    return SchemaRequestDetails(
        schemaName = this.name.toLowerCase(),
        parameters = this.parameters,
        exposeTo = this.exposeTo,
        flavor = this.flavor,
        roles = roles,
        affiliation = affiliation
    )
}