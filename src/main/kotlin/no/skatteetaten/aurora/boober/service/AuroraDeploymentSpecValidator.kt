package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpecInternal
import no.skatteetaten.aurora.boober.model.AuroraSecret
import no.skatteetaten.aurora.boober.model.MountType
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseSchemaProvisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaIdRequest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.StsProvisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.createSchemaDetails
import no.skatteetaten.aurora.boober.service.vault.VaultService
import no.skatteetaten.aurora.boober.utils.takeIfNotEmpty
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.Optional

// TODO: Kan vi fjerne Optional her og bruke nullable?
@Service
class AuroraDeploymentSpecValidator(
    val openShiftClient: OpenShiftClient,
    val openShiftTemplateProcessor: OpenShiftTemplateProcessor,
    val databaseSchemaProvisioner: Optional<DatabaseSchemaProvisioner>,
    val stsProvisioner: Optional<StsProvisioner>,
    val vaultService: VaultService,
    @Value("\${openshift.cluster}") val cluster: String
) {

    val logger: Logger = LoggerFactory.getLogger(AuroraDeploymentSpecValidator::class.java)

    // TODO: We thro on the first validation error, so if there are multiple things wrong it will not show.
    // IMHO exception are wrong here. Return an error result and collect them. If it is nonEmpty then throw.
    @Throws(AuroraDeploymentSpecValidationException::class)
    fun assertIsValid(deploymentSpecInternal: AuroraDeploymentSpecInternal) {

        validateAdminGroups(deploymentSpecInternal)
        validateTemplateIfSet(deploymentSpecInternal)
        validateDatabase(deploymentSpecInternal)
        validateSkap(deploymentSpecInternal)
        validateVaultExistence(deploymentSpecInternal)
        validateExistingResources(deploymentSpecInternal)
        validateSecretVaultFiles(deploymentSpecInternal)
        validateKeyMappings(deploymentSpecInternal)
        validateSecretVaultKeys(deploymentSpecInternal)
        validateDuplicateSecretEnvNames(deploymentSpecInternal)
    }

    fun validateExistingResources(spec: AuroraDeploymentSpecInternal) {
        if (spec.cluster != cluster) return
        val existingMounts = spec.volume?.mounts?.filter { it.exist } ?: return

        val namespace = spec.environment.namespace
        existingMounts.forEach {
            if (!openShiftClient.resourceExists(
                    kind = it.type.kind,
                    namespace = namespace,
                    name = it.volumeName
                )
            ) {
                throw AuroraDeploymentSpecValidationException("Required existing resource with type=${it.type} namespace=$namespace name=${it.volumeName} does not exist.")
            }
        }
    }

    protected fun validateSkap(spec: AuroraDeploymentSpecInternal) {
        if (spec.cluster != cluster) return
        if (stsProvisioner.isPresent) return

        spec.integration?.webseal?.let {
            throw AuroraDeploymentSpecValidationException("No webseal service found in this cluster")
        }
        spec.integration?.certificate?.let {
            throw AuroraDeploymentSpecValidationException("No sts service found in this cluster")
        }
    }

    fun validateVaultExistence(deploymentSpecInternal: AuroraDeploymentSpecInternal) {
        val vaultNames = (deploymentSpecInternal.volume?.mounts
            ?.filter { it.type == MountType.Secret }
            ?.mapNotNull { it.secretVaultName }
            ?: emptyList())
            .toMutableList()

        vaultNames.forEach {
            val vaultCollectionName = deploymentSpecInternal.environment.affiliation
            if (!vaultService.vaultExists(vaultCollectionName, it))
                throw AuroraDeploymentSpecValidationException("Referenced Vault $it in Vault Collection $vaultCollectionName does not exist")
        }
    }

    fun validateDatabase(deploymentSpecInternal: AuroraDeploymentSpecInternal) {
        // We cannot validate database schemas for applications that are not deployed on the current cluster.
        if (deploymentSpecInternal.cluster != cluster) return
        val databases = deploymentSpecInternal.integration?.database
        if (databases.isNullOrEmpty()) {
            return
        }

        val dbProvisioner = databaseSchemaProvisioner.orElseThrow {
            AuroraDeploymentSpecValidationException("No database service found in this cluster")
        }

        databases.filter { it.id != null }
            .map { SchemaIdRequest(it.id!!, it.createSchemaDetails(deploymentSpecInternal.environment.affiliation)) }
            .forEach {
                try {
                    dbProvisioner.findSchemaById(it.id, it.details)
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

        val sumMembers = adminGroups.sumBy {
            openShiftGroups.groupUsers[it]?.size ?: 0
        }

        if (0 == sumMembers) {
            throw AuroraDeploymentSpecValidationException("All groups=[${adminGroups.joinToString(", ")}] are empty")
        }
    }

    private fun validateTemplateIfSet(deploymentSpecInternal: AuroraDeploymentSpecInternal) {

        deploymentSpecInternal.localTemplate?.let { template ->
            openShiftTemplateProcessor.validateTemplateParameters(
                template.templateJson,
                template.parameters ?: emptyMap()
            ).takeIf { it.isNotEmpty() }
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

    fun validateKeyMappings(deploymentSpecInternal: AuroraDeploymentSpecInternal) {
        deploymentSpecInternal.volume?.secrets?.forEach { secret ->
            validateKeyMapping(secret)
        }
    }

    private fun validateKeyMapping(secret: AuroraSecret): Boolean {
        val keyMappings = secret.keyMappings.takeIfNotEmpty() ?: return true
        val keys = secret.secretVaultKeys.takeIfNotEmpty() ?: return true
        val diff = keyMappings.keys - keys
        if (diff.isNotEmpty()) {
            throw AuroraDeploymentSpecValidationException("The secretVault keyMappings $diff were not found in keys")
        }
        return false
    }

    /**
     * Validates that any secretVaultKeys specified actually exist in the vault.
     * Note that this method always uses the latest.properties file regardless of the version of the application and
     * the contents of the vault.
     */
    fun validateSecretVaultKeys(deploymentSpecInternal: AuroraDeploymentSpecInternal) {

        deploymentSpecInternal.volume?.secrets?.forEach { secret ->
            validateSecretVaultKey(secret, deploymentSpecInternal)
        }
    }

    private fun validateSecretVaultKey(
        secret: AuroraSecret,
        deploymentSpecInternal: AuroraDeploymentSpecInternal
    ): Boolean {
        val vaultName = secret.secretVaultName
        val keys = secret.secretVaultKeys.takeIfNotEmpty() ?: return true

        val vaultKeys = vaultService.findVaultKeys(
            deploymentSpecInternal.environment.affiliation,
            vaultName,
            secret.file
        )
        val missingKeys = keys - vaultKeys
        if (missingKeys.isNotEmpty()) {
            throw AuroraDeploymentSpecValidationException("The keys $missingKeys were not found in the secret vault")
        }
        return false
    }

    fun validateSecretVaultFiles(deploymentSpecInternal: AuroraDeploymentSpecInternal) {
        deploymentSpecInternal.volume?.secrets?.forEach { secret ->
            validateSecretVaultFile(secret, deploymentSpecInternal)
        }
    }

    private fun validateSecretVaultFile(secret: AuroraSecret, deploymentSpecInternal: AuroraDeploymentSpecInternal) {
        try {
            vaultService.findFileInVault(
                vaultCollectionName = deploymentSpecInternal.environment.affiliation,
                vaultName = secret.secretVaultName,
                fileName = secret.file
            )
        } catch (e: Exception) {
            throw AuroraDeploymentSpecValidationException("File with name=${secret.file} is not present in vault=${secret.secretVaultName} in collection=${deploymentSpecInternal.environment.affiliation}")
        }
    }

    /*
     * Validates that the name property of a secret it unique
     */
    @Throws(AuroraDeploymentSpecValidationException::class)
    fun validateDuplicateSecretEnvNames(deploymentSpecInternal: AuroraDeploymentSpecInternal) {
        val secretNames = deploymentSpecInternal.volume?.secrets?.map { it.name } ?: emptyList()
        if (secretNames.size != secretNames.toSet().size) {
            throw AuroraDeploymentSpecValidationException(
                "SecretVaults does not have unique names=[${secretNames.joinToString(
                    ", "
                )}]"
            )
        }
    }
}