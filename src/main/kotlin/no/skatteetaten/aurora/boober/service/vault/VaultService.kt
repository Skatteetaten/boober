package no.skatteetaten.aurora.boober.service.vault

import no.skatteetaten.aurora.boober.Domain.VAULT
import no.skatteetaten.aurora.boober.TargetDomain
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.service.AuroraVaultServiceException
import no.skatteetaten.aurora.boober.service.EncryptionService
import no.skatteetaten.aurora.boober.service.GitService
import no.skatteetaten.aurora.boober.service.UnauthorizedAccessException
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import org.eclipse.jgit.api.Git
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.support.PropertiesLoaderUtils
import org.springframework.stereotype.Service

data class VaultWithAccess(
    val vault: EncryptedFileVault?, // Will be null if the user does not have access
    val vaultName: String,
    val hasAccess: Boolean = true
) {
    companion object {
        fun create(vault: EncryptedFileVault, user: User): VaultWithAccess {
            val hasAccess = user.hasAccess(vault.permissions)
            return VaultWithAccess(if (hasAccess) vault else null, vault.name, hasAccess)
        }
    }
}

@Service
class VaultService(
    @TargetDomain(VAULT)
    val gitService: GitService,
    val encryptionService: EncryptionService,
    val userDetailsProvider: UserDetailsProvider
) {
    fun findVaultKeys(vaultCollectionName: String, vaultName: String, fileName: String): Set<String> {
        val vaultCollection = findVaultCollection(vaultCollectionName)
        val vault = vaultCollection.findVaultByName(vaultName) ?: return emptySet()
        val content = vault.secrets[fileName] ?: return emptySet()
        return PropertiesLoaderUtils.loadProperties(ByteArrayResource(content)).stringPropertyNames()
    }

    fun findFileInVault(
        vaultCollectionName: String,
        vaultName: String,
        fileName: String
    ): ByteArray {
        val vault = findVault(vaultCollectionName, vaultName)
        return vault.getFile(fileName)
    }

    fun findAllPublicVaults(vaultCollectionName: String): List<String> {
        return findAllVaultsInVaultCollection(vaultCollectionName).filter {
            it.publicVault
        }.map { it.name }
    }

    fun findAllVaultsWithUserAccessInVaultCollection(vaultCollectionName: String): List<VaultWithAccess> {

        val authenticatedUser = userDetailsProvider.getAuthenticatedUser()

        return findAllVaultsInVaultCollection(vaultCollectionName)
            .map { VaultWithAccess.create(it, authenticatedUser) }
    }

    fun findVault(vaultCollectionName: String, vaultName: String): EncryptedFileVault {

        val vaultCollection = findVaultCollection(vaultCollectionName)
        return findVault(vaultCollection, vaultName)
    }

    fun findVault(vaultCollection: VaultCollection, vaultName: String) =
        (findVaultByNameIfAllowed(vaultCollection, vaultName)
            ?: throw IllegalArgumentException("Vault not found name=$vaultName"))

    fun vaultExists(vaultCollectionName: String, vaultName: String): Boolean {
        return withVaultCollectionAndRepoForUpdate(vaultCollectionName) { vaultCollection, _ ->
            vaultCollection.findVaultByName(vaultName) != null
        }
    }

    fun createOrUpdateFileInVault(
        vaultCollectionName: String,
        vaultName: String,
        fileName: String,
        fileContents: ByteArray,
        previousSignature: String? = null
    ): EncryptedFileVault {

        assertSecretKeysAreValid(mapOf(fileName to fileContents))
        return withVaultCollectionAndRepoForUpdate(vaultCollectionName) { vaultCollection, repo ->
            val vault = findVaultByNameIfAllowed(vaultCollection, vaultName) ?: vaultCollection.createVault(vaultName)

            vault.updateFile(fileName, fileContents, previousSignature)
            gitService.commitAndPushChanges(repo)
            vault
        }
    }

    fun deleteFileInVault(vaultCollectionName: String, vaultName: String, fileName: String): EncryptedFileVault? {

        return withVaultCollectionAndRepoForUpdate(vaultCollectionName) { vaultCollection, repo ->
            val vault = findVault(vaultCollection, vaultName)
            vault.deleteFile(fileName)
            gitService.commitAndPushChanges(repo)
            vault
        }
    }

    fun deleteVault(vaultCollectionName: String, vaultName: String) {
        return withVaultCollectionAndRepoForUpdate(vaultCollectionName) { vaultCollection, repo ->
            if (vaultName.isBlank()) {
                throw IllegalArgumentException("Vault name can not be empty")
            }

            val vault = findVaultByNameIfAllowed(vaultCollection, vaultName) ?: return@withVaultCollectionAndRepoForUpdate

            vault.vaultFolder.deleteRecursively()
            gitService.commitAndPushChanges(repo = repo, rmFilePattern = vault.vaultFolder.name, addFilePattern = null)
        }
    }

    fun setVaultPermissions(vaultCollectionName: String, vaultName: String, groupPermissions: List<String>) {

        assertCurrentUserHasAccess(groupPermissions)
        return withVaultCollectionAndRepoForUpdate(vaultCollectionName) { vaultCollection, repo ->
            val vault = findVault(vaultCollection, vaultName)
            vault.permissions = groupPermissions
            gitService.commitAndPushChanges(repo)
        }
    }

    fun import(
        vaultCollectionName: String,
        vaultName: String,
        permissions: List<String>,
        secrets: Map<String, ByteArray>
    ): EncryptedFileVault {

        if (permissions.isEmpty()) {
            throw IllegalArgumentException("Public vaults are not allowed. Please specify atleast one permisssion group.")
        }

        assertCurrentUserHasAccess(permissions)

        assertSecretKeysAreValid(secrets)
        return withVaultCollectionAndRepoForUpdate(vaultCollectionName) { vaultCollection, repo ->
            findVaultByNameIfAllowed(vaultCollection, vaultName)

            vaultCollection.createVault(vaultName).let { vault ->
                vault.clear()
                secrets.forEach { (name, contents) -> vault.updateFile(name, contents) }
                vault.permissions = permissions
                gitService.commitAndPushChanges(repo)
                vault
            }
        }
    }

    fun reencryptVaultCollection(vaultCollectionName: String, newKey: String) {

        val vaults = findAllVaultsInVaultCollection(vaultCollectionName)
        vaults.forEach { vault: EncryptedFileVault ->
            val newEncryptionService =
                EncryptionService(newKey, encryptionService.keyFactory, encryptionService.metrics)
            val vaultCopy = EncryptedFileVault.createFromFolder(
                vault.vaultFolder,
                newEncryptionService::encrypt,
                encryptionService::decrypt
            )
            vaultCopy.secrets.forEach { t, u -> vaultCopy.updateFile(t, u) }
        }
    }

    private fun findVaultByNameIfAllowed(vaultCollection: VaultCollection, vaultName: String): EncryptedFileVault? {

        val vault = vaultCollection.findVaultByName(vaultName)
        return vault?.apply { assertCurrentUserHasAccess(this.permissions) }
    }

    private fun assertCurrentUserHasAccess(permissions: List<String>) {
        val user = userDetailsProvider.getAuthenticatedUser()

        if (!user.hasAccess(permissions)) {
            val message = "You (${user.username}) do not have required permissions to " +
                "operate on this vault. You have ${user.authorities.map { it.authority }}"
            throw UnauthorizedAccessException(message)
        }
    }

    private fun <T> withVaultCollectionAndRepoForUpdate(
        vaultCollectionName: String,
        function: (vaultCollection: VaultCollection, repo: Git) -> T
    ): T {

        return synchronized(vaultCollectionName) {

            val repo = gitService.checkoutRepository(vaultCollectionName, refName = "master")
            val folder = repo.repository.directory.parentFile
            val vaultCollection =
                VaultCollection.fromFolder(folder, encryptionService::encrypt, encryptionService::decrypt)
            val response = try {
                function(vaultCollection, repo)
            } catch (e: Exception) {
                throw AuroraVaultServiceException(
                    "Could not update auroraVault underlying message=${e.localizedMessage}",
                    e
                )
            }
            repo.close()
            response
        }
    }

    fun findVaultCollection(vaultCollectionName: String): VaultCollection {

        return withVaultCollectionAndRepoForUpdate(vaultCollectionName, { vaultCollection, repo -> vaultCollection })
    }

    private fun findAllVaultsInVaultCollection(vaultCollectionName: String): List<EncryptedFileVault> {

        return findVaultCollection(vaultCollectionName).vaults
    }

    companion object {
        val rePattern = "^[-._a-zA-Z0-9]+$"
        val re = Regex(rePattern)

        // Note that a properties file can be delimitered by space and =, very few people know this so we check for it
        fun assertSecretKeysAreValid(secrets: Map<String, ByteArray>) {
            val filesToKeys = secrets.filter { it.key.endsWith(".properties") }
                .mapValues {
                    String(it.value)
                        .lines()
                        .filter { !it.isBlank() }
                        .map { it.substringBefore("=") }
                        .filter { !it.matches(re) }
                }

            val invalidKeys = filesToKeys.flatMap { fileToKey ->
                fileToKey.value.map { "${fileToKey.key}/$it" }
            }

            if (invalidKeys.isNotEmpty()) {
                throw IllegalArgumentException("Vault key=$invalidKeys is not valid. Regex used for matching $rePattern")
            }
        }
    }
}
