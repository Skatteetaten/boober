package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.MountType
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseSchemaProvisioner
import no.skatteetaten.aurora.boober.service.vault.VaultService
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
        @Value("\${openshift.cluster}") val cluster: String) {


    val logger: Logger = LoggerFactory.getLogger(AuroraDeploymentSpecValidator::class.java)

    @Throws(AuroraDeploymentSpecValidationException::class)
    fun assertIsValid(deploymentSpec: AuroraDeploymentSpec) {

        validateAdminGroups(deploymentSpec)
        validateTemplateIfSet(deploymentSpec)
        validateDatabaseId(deploymentSpec)
        validateVaultExistence(deploymentSpec)
    }

    protected fun validateVaultExistence(deploymentSpec: AuroraDeploymentSpec) {

        val vaultNames = (deploymentSpec.volume?.mounts
                ?.filter { it.type == MountType.Secret }
                ?.mapNotNull { it.secretVaultName }
                ?: emptyList())
                .toMutableList()
        deploymentSpec.volume?.secretVaultName?.let { vaultNames.add(it) }

        vaultNames.forEach {
            val vaultCollectionName = deploymentSpec.environment.affiliation
            if (!vaultService.vaultExists(vaultCollectionName, it))
                throw AuroraDeploymentSpecValidationException("Referenced Vault $it in Vault Collection $vaultCollectionName does not exist")
        }
    }

    protected fun validateDatabaseId(deploymentSpec: AuroraDeploymentSpec) {
        // We cannot validate database schemas for applications that are not deployed on the current cluster.
        if (deploymentSpec.cluster != cluster) return
        val databases = deploymentSpec.deploy?.database ?: return
        databases.mapNotNull { it.id }
                .forEach {
                    try {
                        databaseSchemaProvisioner.findSchemaById(it)
                    } catch (e: Exception) {
                        throw AuroraDeploymentSpecValidationException("Database schema with id=$it does not exist")
                    }
                }
    }

    private fun validateAdminGroups(deploymentSpec: AuroraDeploymentSpec) {

        val adminGroups: Set<String> = deploymentSpec.environment.permissions.admin.groups ?: setOf()
        adminGroups.takeIf { it.isEmpty() }
                ?.let { throw AuroraDeploymentSpecValidationException("permissions.admin.groups cannot be empty") }

        val openShiftGroups = openShiftClient.getGroups()

        val nonExistantDeclaredGroups = adminGroups.filter { !openShiftGroups.groupExist(it) }
        if (nonExistantDeclaredGroups.isNotEmpty()) {
            throw AuroraDeploymentSpecValidationException("$nonExistantDeclaredGroups are not valid groupNames")
        }
    }

    private fun validateTemplateIfSet(deploymentSpec: AuroraDeploymentSpec) {

        deploymentSpec.localTemplate?.let {
            openShiftTemplateProcessor.validateTemplateParameters(it.templateJson, it.parameters ?: emptyMap())
                    .takeIf { it.isNotEmpty() }
                    ?.let { throw AuroraDeploymentSpecValidationException(it.joinToString(". ").trim()) }
        }

        deploymentSpec.template?.let {
            val templateJson = openShiftClient.getTemplate(it.template)
                    ?: throw AuroraDeploymentSpecValidationException("Template ${it.template} does not exist")
            openShiftTemplateProcessor.validateTemplateParameters(templateJson, it.parameters ?: emptyMap())
                    .takeIf { it.isNotEmpty() }
                    ?.let { throw AuroraDeploymentSpecValidationException(it.joinToString(". ").trim()) }
        }
    }
}