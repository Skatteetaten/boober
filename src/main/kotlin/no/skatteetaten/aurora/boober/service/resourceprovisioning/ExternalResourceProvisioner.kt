package no.skatteetaten.aurora.boober.service.resourceprovisioning

import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpecInternal
import no.skatteetaten.aurora.boober.model.AuroraSecret
import no.skatteetaten.aurora.boober.model.Database
import no.skatteetaten.aurora.boober.utils.filterProperties
import org.springframework.stereotype.Service
import java.util.Optional

class ProvisioningResult(
    val schemaProvisionResults: SchemaProvisionResults? = null,
    val vaultResults: VaultResults? = null,
    val stsProvisioningResult: StsProvisioningResult? = null,
    val vaultSecretEnvResult: List<VaultSecretEnvResult> = emptyList()
)

@Service
class ExternalResourceProvisioner(
    val databaseSchemaProvisioner: Optional<DatabaseSchemaProvisioner>,
    val stsProvisioner: Optional<StsProvisioner>,
    val vaultProvider: VaultProvider
) {

    fun provisionResources(deploymentSpecInternal: AuroraDeploymentSpecInternal): ProvisioningResult {

        val stsProvisioningResult = handleSts(deploymentSpecInternal)
        val schemaProvisionResult = handleSchemaProvisioning(deploymentSpecInternal)
        val vaultResults = handleVaults(deploymentSpecInternal)
        val secretEnvResults = handleSecretEnv(deploymentSpecInternal)

        return ProvisioningResult(schemaProvisionResult, vaultResults, stsProvisioningResult, secretEnvResults)
    }

    private fun handleSecretEnv(deploymentSpecInternal: AuroraDeploymentSpecInternal): List<VaultSecretEnvResult> {
        return deploymentSpecInternal.volume?.secrets?.mapNotNull { secret: AuroraSecret ->
            val request = VaultRequest(
                collectionName = deploymentSpecInternal.environment.affiliation,
                name = secret.secretVaultName,
                keys = secret.secretVaultKeys,
                keyMappings = secret.keyMappings
            )
            vaultProvider.findVaultData(request)[secret.file]?.let { file ->
                val properties = filterProperties(file, secret.secretVaultKeys, secret.keyMappings)
                properties.map {
                    it.key.toString() to it.value.toString().toByteArray()
                }
            }?.let {
                VaultSecretEnvResult(secret.name, it.toMap())
            }
        } ?: emptyList()
    }

    private fun handleSts(deploymentSpec: AuroraDeploymentSpecInternal): StsProvisioningResult? {
        return deploymentSpec.integration?.certificate?.let {
            val sts = stsProvisioner.orElseThrow {
                IllegalArgumentException("Sts is not provided")
            }

            sts.generateCertificate(it, deploymentSpec.name, deploymentSpec.environment.envName)
        }
    }

    private fun handleSchemaProvisioning(deploymentSpecInternal: AuroraDeploymentSpecInternal): SchemaProvisionResults? {
        val schemaProvisionRequests = createSchemaProvisionRequestsFromDeploymentSpec(deploymentSpecInternal)
        if (schemaProvisionRequests.isEmpty()) {
            return null
        }
        val dbService = databaseSchemaProvisioner.orElseThrow {
            IllegalArgumentException("No database provisioner provided")
        }

        return dbService.provisionSchemas(schemaProvisionRequests)
    }

    private fun handleVaults(deploymentSpecInternal: AuroraDeploymentSpecInternal): VaultResults? {

        val vaultRequests = deploymentSpecInternal.volume?.mounts?.mapNotNull { it.secretVaultName }?.map {
            VaultRequest(
                collectionName = deploymentSpecInternal.environment.affiliation,
                name = it
            )
        } ?: emptyList()
        return vaultProvider.findVaultData(vaultRequests)
    }

    companion object {
        @JvmStatic
        fun createSchemaProvisionRequestsFromDeploymentSpec(deploymentSpecInternal: AuroraDeploymentSpecInternal): List<SchemaProvisionRequest> {
            val databaseSpecs = deploymentSpecInternal.integration?.database ?: listOf()
            // TODO: If we want to support non managed databsaes we need to filter the flavor on managed here.
            return databaseSpecs.map {

                val details = it.createSchemaDetails(deploymentSpecInternal.environment.affiliation)
                if (it.id != null) {
                    SchemaIdRequest(
                        id = it.id,
                        details = details
                    )
                } else {
                    SchemaForAppRequest(
                        environment =
                        deploymentSpecInternal.environment.envName,
                        application = deploymentSpecInternal.name,
                        details = details,
                        generate = it.generate
                    )
                }
            }
        }
    }
}

fun Database.createSchemaDetails(affiliation: String): SchemaRequestDetails {

    val users = if (this.roles.isEmpty()) {
        listOf(SchemaUser(name = "SCHEMA", role = "a", affiliation = affiliation))
    } else this.roles.map { role ->
        val exportedRole = this.exposeTo.filter { it.value == role.key }.map { it.key }.firstOrNull()
        val userAffiliation = exportedRole ?: affiliation
        SchemaUser(name = role.key, role = role.value.permissionString, affiliation = userAffiliation)
    }

    return SchemaRequestDetails(
        schemaName = this.name.toLowerCase(),
        databaseInstance = this.instance,
        affiliation = affiliation,
        users = users,
        engine = this.flavor.engine
    )
}
