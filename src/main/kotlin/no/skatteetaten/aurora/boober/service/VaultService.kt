package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.model.Vault
import no.skatteetaten.aurora.boober.model.VaultCollection
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

data class AuroraSecretVaultWithAccess @JvmOverloads constructor(
        val vault: Vault,
        val hasAccess: Boolean = true
)

@Service
class VaultService(
        val gitService: GitService,
        val encryptionService: EncryptionService,
        val permissionService: PermissionService
) {

    private val logger = LoggerFactory.getLogger(VaultService::class.java)

    fun findVaultCollection(vaultCollectionName: String): VaultCollection {

        return withVaultCollectionAndRepo(vaultCollectionName, { vaultCollection, repo -> vaultCollection })
    }

    fun findAllVaultsInVaultCollection(vaultCollectionName: String): List<Vault> {

        return findVaultCollection(vaultCollectionName).vaults
    }

    fun findAllVaultsWithUserAccessInVaultCollection(vaultCollectionName: String): List<AuroraSecretVaultWithAccess> {

        return findAllVaultsInVaultCollection(vaultCollectionName)
                .map { AuroraSecretVaultWithAccess(it, permissionService.hasUserAccess(it.permissions)) }
    }

    fun findVault(vaultCollectionName: String, vaultName: String): Vault {

        return findAllVaultsInVaultCollection(vaultCollectionName).find { it.name == vaultName }
                ?: throw IllegalArgumentException("Vault not found name=$vaultName")
    }

    fun updateSecretFile(vaultCollectionName: String,
                         vaultName: String,
                         fileName: String,
                         fileContents: String
    ): Vault {

        return withVaultCollectionAndRepo(vaultCollectionName, { vaultCollection, repo ->
            val vault = vaultCollection.findVaultByName(vaultName) ?: vaultCollection.createVault(vaultName)

            if (!permissionService.hasUserAccess(vault.permissions)) {
                throw IllegalAccessError("You do not have permission to operate on this vaultName ($vaultName)")
            }

            vault.updateFile(fileName, fileContents)
            commitAndPushVaultChanges(repo, vaultName)
            vault
        })
    }

    private fun <T> withVaultCollectionAndRepo(vaultCollectionName: String, function: (vaultCollection: VaultCollection, repo: Git) -> T): T {

        val repo = gitService.checkoutRepository(vaultCollectionName)
        val folder = repo.repository.directory.parentFile
        val vaultCollection = VaultCollection.fromFolder(folder, encryptionService::encrypt, encryptionService::decrypt)
        val response = function(vaultCollection, repo)
        repo.close()

        return response
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

    fun save(affiliation: String, vault: Vault, validateVersions: Boolean): Vault {
        return vault
    }

    fun delete(affiliation: String, vault: String): Boolean {
        val repo = gitService.checkoutRepository(affiliation)

//        gitService.deleteDirectory(repo, "$GIT_SECRET_FOLDER/$vault/")
        return true
    }
}
