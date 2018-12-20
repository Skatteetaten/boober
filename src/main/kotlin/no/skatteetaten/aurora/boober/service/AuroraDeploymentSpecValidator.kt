package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpecInternal
import no.skatteetaten.aurora.boober.model.MountType
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseSchemaProvisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaIdRequest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.createSchemaDetails
import no.skatteetaten.aurora.boober.service.vault.VaultService
import no.skatteetaten.aurora.boober.utils.takeIfNotEmpty
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class AuroraDeploymentSpecValidator(
    val openShiftClient: OpenShiftClient,
    val openShiftTemplateProcessor: OpenShiftTemplateProcessor,
    val databaseSchemaProvisioner: DatabaseSchemaProvisioner,
    val vaultService: VaultService,
    @Value("\${openshift.cluster}") val cluster: String
) {

    val logger: Logger = LoggerFactory.getLogger(AuroraDeploymentSpecValidator::class.java)

    @Throws(AuroraDeploymentSpecValidationException::class)
    fun assertIsValid(deploymentSpecInternal: AuroraDeploymentSpecInternal) {

        validateAdminGroups(deploymentSpecInternal)
        validateTemplateIfSet(deploymentSpecInternal)
        validateDatabaseId(deploymentSpecInternal)
        validateVaultExistence(deploymentSpecInternal)
        validateKeyMappings(deploymentSpecInternal)
        validateSecretVaultKeys(deploymentSpecInternal)
    }

    protected fun validateVaultExistence(deploymentSpecInternal: AuroraDeploymentSpecInternal) {

        val vaultNames = (deploymentSpecInternal.volume?.mounts
            ?.filter { it.type == MountType.Secret }
            ?.mapNotNull { it.secretVaultName }
            ?: emptyList())
            .toMutableList()
        deploymentSpecInternal.volume?.secretVaultName?.let { vaultNames.add(it) }

        vaultNames.forEach {
            val vaultCollectionName = deploymentSpecInternal.environment.affiliation
            if (!vaultService.vaultExists(vaultCollectionName, it))
                throw AuroraDeploymentSpecValidationException("Referenced Vault $it in Vault Collection $vaultCollectionName does not exist")
        }
    }

    protected fun validateDatabaseId(deploymentSpecInternal: AuroraDeploymentSpecInternal) {
        // We cannot validate database schemas for applications that are not deployed on the current cluster.
        if (deploymentSpecInternal.cluster != cluster) return
        val databases = deploymentSpecInternal.integration?.database ?: return
        databases.filter { it.id != null }
            .map { SchemaIdRequest(it.id!!, it.createSchemaDetails(deploymentSpecInternal.environment.affiliation)) }
            .forEach {
                try {
                    databaseSchemaProvisioner.findSchemaById(it.id, it.details)
                } catch (e: Exception) {
                    throw AuroraDeploymentSpecValidationException("Database schema with id=${it.id} and affiliation=${it.details.affiliation} does not exist")
                }
            }
    }

    private fun validateAdminGroups(deploymentSpecInternal: AuroraDeploymentSpecInternal) {

        val adminGroups: Set<String> = deploymentSpecInternal.environment.permissions.admin.groups ?: setOf()
        adminGroups.takeIf { it.isEmpty() }
            ?.let { throw AuroraDeploymentSpecValidationException("permissions.admin.groups cannot be empty") }

        val openShiftGroups = openShiftClient.getGroups()

        val nonExistantDeclaredGroups = adminGroups.filter { !openShiftGroups.groupExist(it) }
        if (nonExistantDeclaredGroups.isNotEmpty()) {
            throw AuroraDeploymentSpecValidationException("$nonExistantDeclaredGroups are not valid groupNames")
        }
    }

    private fun validateTemplateIfSet(deploymentSpecInternal: AuroraDeploymentSpecInternal) {

        deploymentSpecInternal.localTemplate?.let {
            openShiftTemplateProcessor.validateTemplateParameters(it.templateJson, it.parameters ?: emptyMap())
                .takeIf { it.isNotEmpty() }
                ?.let { throw AuroraDeploymentSpecValidationException(it.joinToString(". ").trim()) }
        }

        deploymentSpecInternal.template?.let {
            val templateJson = openShiftClient.getTemplate(it.template)
                ?: throw AuroraDeploymentSpecValidationException("Template ${it.template} does not exist")
            openShiftTemplateProcessor.validateTemplateParameters(templateJson, it.parameters ?: emptyMap())
                .takeIf { it.isNotEmpty() }
                ?.let { throw AuroraDeploymentSpecValidationException(it.joinToString(". ").trim()) }
        }
    }

    protected fun validateKeyMappings(deploymentSpecInternal: AuroraDeploymentSpecInternal) {
        deploymentSpecInternal.volume?.let { volume ->
            val keyMappings = volume.keyMappings.takeIfNotEmpty() ?: return
            val keys = volume.secretVaultKeys.takeIfNotEmpty() ?: return
            val diff = keyMappings.keys - keys
            if (diff.isNotEmpty()) {
                throw AuroraDeploymentSpecValidationException("The secretVault keyMappings $diff were not found in keys")
            }
        }
    }

    /**
     * Validates that any secretVaultKeys specified actually exist in the vault.
     * Note that this method always uses the latest.properties file regardless of the version of the application and
     * the contents of the vault. TODO: to determine if another properties file should be used instead.
     */
    protected fun validateSecretVaultKeys(deploymentSpecInternal: AuroraDeploymentSpecInternal) {

        deploymentSpecInternal.volume?.let { volume ->
            val vaultName = volume.secretVaultName ?: return
            val keys = volume.secretVaultKeys.takeIfNotEmpty() ?: return

            val vaultKeys = vaultService.findVaultKeys(
                deploymentSpecInternal.environment.affiliation,
                vaultName,
                "latest.properties"
            )
            val missingKeys = keys - vaultKeys
            if (missingKeys.isNotEmpty()) {
                throw AuroraDeploymentSpecValidationException("The keys $missingKeys were not found in the secret vault")
            }
        }
    }
}