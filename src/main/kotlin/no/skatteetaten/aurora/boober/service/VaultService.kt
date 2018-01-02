package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.model.EncryptedFileVault
import no.skatteetaten.aurora.boober.model.VaultCollection
import no.skatteetaten.aurora.boober.service.GitServices.Domain.VAULT
import no.skatteetaten.aurora.boober.service.GitServices.TargetDomain
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

data class VaultWithAccess @JvmOverloads constructor(
        val vault: EncryptedFileVault?, // Will be null if the user does not have access
        val vaultName: String,
        val hasAccess: Boolean = true
) {
    companion object {
        fun create(vault: EncryptedFileVault, user: User): VaultWithAccess {
            val hasAccess = user.hasAnyRole(vault.permissions?.groups)
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

    private val logger = LoggerFactory.getLogger(VaultService::class.java)

    fun findVaultCollection(vaultCollectionName: String): VaultCollection {

        return withVaultCollectionAndRepoForUpdate(vaultCollectionName, { vaultCollection, repo -> vaultCollection })
    }

    fun findAllVaultsInVaultCollection(vaultCollectionName: String): List<EncryptedFileVault> {

        return findVaultCollection(vaultCollectionName).vaults
    }

    fun findAllVaultsWithUserAccessInVaultCollection(vaultCollectionName: String): List<VaultWithAccess> {

        val authenticatedUser = userDetailsProvider.getAuthenticatedUser()

        return findAllVaultsInVaultCollection(vaultCollectionName)
                .map { VaultWithAccess.create(it, authenticatedUser) }
    }

    fun findVault(vaultCollectionName: String, vaultName: String): EncryptedFileVault {

        return findAllVaultsInVaultCollection(vaultCollectionName).find { it.name == vaultName }
                ?: throw IllegalArgumentException("Vault not found name=$vaultName")
    }

    fun createOrUpdateFileInVault(vaultCollectionName: String,
                                  vaultName: String,
                                  fileName: String,
                                  fileContents: String
    ): EncryptedFileVault {

        return withVaultCollectionAndRepoForUpdate(vaultCollectionName, { vaultCollection, repo ->
            val vault = vaultCollection.findVaultByName(vaultName) ?: vaultCollection.createVault(vaultName)
            assertCurrentUserHasAccess(vault)

            vault.updateFile(fileName, fileContents)
            commitAndPushVaultChanges(repo, vault.name)
            vault
        })
    }

    fun deleteFileInVault(vaultCollectionName: String, vaultName: String, fileName: String): EncryptedFileVault? {

        return withVaultCollectionAndRepoForUpdate(vaultCollectionName, { vaultCollection, repo ->
            val vault = vaultCollection.findVaultByName(vaultName) ?: return@withVaultCollectionAndRepoForUpdate null
            assertCurrentUserHasAccess(vault)

            vault.deleteFile(fileName)
            commitAndPushVaultChanges(repo, vault.name)
            vault
        })
    }

    fun deleteVault(vaultCollectionName: String, vaultName: String) {
        return withVaultCollectionAndRepoForUpdate(vaultCollectionName, { vaultCollection, repo ->
            val vault = vaultCollection.findVaultByName(vaultName) ?: return@withVaultCollectionAndRepoForUpdate
            assertCurrentUserHasAccess(vault)

            vaultCollection.deleteVault(vaultName)
            commitAndPushVaultChanges(repo, vault.name)
        })
    }

    private fun assertCurrentUserHasAccess(vault: EncryptedFileVault) {
        if (!userDetailsProvider.getAuthenticatedUser().hasAnyRole(vault.permissions?.groups)) {
            throw IllegalAccessError("You do not have permission to operate on this vault (${vault.name})")
        }
    }

    private fun <T> withVaultCollectionAndRepoForUpdate(vaultCollectionName: String, function: (vaultCollection: VaultCollection, repo: Git) -> T): T {

        return synchronized(vaultCollectionName) {

            val repo = gitService.checkoutRepository(vaultCollectionName)
            val folder = repo.repository.directory.parentFile
            val vaultCollection = VaultCollection.fromFolder(folder, encryptionService::encrypt, encryptionService::decrypt)
            val response = function(vaultCollection, repo)
            repo.close()
            response
        }
    }

    private fun commitAndPushVaultChanges(repo: Git, vaultName: String) {

        repo.add().addFilepattern(vaultName).call()
        repo.commit()
                .setAll(true)
                .setAllowEmpty(false)
                .setAuthor(PersonIdent("anonymous", "anonymous@skatteetaten.no"))
                .setMessage("")
                .call()
        repo.push()
                //                .setCredentialsProvider(cp)
                .add("refs/heads/master")
                .call()
    }

    fun save(vaultCollectionName: String, vault: EncryptedFileVault, validateVersions: Boolean): EncryptedFileVault {
        return vault
    }
}
