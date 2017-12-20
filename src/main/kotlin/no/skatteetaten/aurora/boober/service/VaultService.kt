package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.model.Vault
import no.skatteetaten.aurora.boober.model.VaultCollection
import org.apache.commons.io.FileUtils
import org.eclipse.jgit.api.Git
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File
import java.nio.charset.Charset

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

        val repo = gitService.checkoutRepository(vaultCollectionName)
        val folder = repo.repository.directory.parentFile
        repo.close()

        return VaultCollection.fromFolder(folder, encryptionService::decrypt)
    }

    fun findAllVaultsWithUserAccessInVaultCollection(vaultCollectionName: String): List<AuroraSecretVaultWithAccess> {

        return findAllVaultsInVaultCollection(vaultCollectionName)
                .map { AuroraSecretVaultWithAccess(it, permissionService.hasUserAccess(it.permissions)) }
    }

    fun findAllVaultsInVaultCollection(vaultCollectionName: String): List<Vault> {

        return findVaultCollection(vaultCollectionName).vaults
    }

    fun findVault(vaultCollectionName: String, vaultName: String): Vault {

        return findAllVaultsInVaultCollection(vaultCollectionName).find { it.name == vaultName }
                ?: throw IllegalArgumentException("Vault not found name=$vaultName")
    }

    fun updateSecretFile(vaultCollectionName: String,
                         vaultName: String,
                         fileName: String,
                         fileContents: String,
                         fileVersion: String,
                         validateVersions: Boolean): Vault {

        val vaultCollection = findVaultCollection(vaultCollectionName)

        val vaultPath = File(vaultCollection.path, vaultName)
        val secretFile = File(vaultPath, fileName)
        FileUtils.forceMkdirParent(secretFile)
        FileUtils.write(secretFile, fileContents, Charset.defaultCharset())

        return VaultCollection
                .fromFolder(File(vaultCollection.path.absolutePath), encryptionService::decrypt)
                .findVaultByName(vaultName)!!
/*
        val vault = vaultCollection.findVaultByName(vaultName)
        if (vault == null) {
            val vaultFolder =
            vaultFolder.mkdirs()
            File(vaultFolder, ".permissions").createNewFile()
        }

        if (!permissionService.hasUserAccess(vault.permissions)) {
            throw IllegalAccessError("You do not have permission to operate on this vaultName ($vaultName)")
        }
*/
/*

        return withAuroraVault(affiliation, vaultName, validateVersions, function = {
            val newVersions = it.versions + (fileName to fileVersion)
            val newSecrets = it.secrets + (fileName to fileContents)
            it.copy(versions = newVersions, secrets = newSecrets)
        })
*/

    }

    fun save(affiliation: String, vault: Vault, validateVersions: Boolean): Vault {
        return withAuroraVault(affiliation, vault.name, validateVersions, function = {
            vault
        })
    }

    fun delete(affiliation: String, vault: String): Boolean {
        val repo = gitService.checkoutRepository(affiliation)

//        gitService.deleteDirectory(repo, "$GIT_SECRET_FOLDER/$vault/")
        return true
    }

    private fun withAuroraVault(vaultCollectionName: String,
                                vaultName: String,
                                validateVersions: Boolean,
                                commitChanges: Boolean = true,
                                function: (Vault) -> Vault = { it -> it }): Vault {
        val vaultCollection = findVaultCollection(vaultCollectionName)

        val oldVault = vaultCollection.findVaultByName(vaultName)

/*

        if (!permissionService.hasUserAccess(oldVault.permissions)) {
            throw IllegalAccessError("You do not have permission to operate on this vault ($vaultName)")
        }

        val newVault = function(oldVault)
        if (!permissionService.hasUserAccess(newVault.permissions)) {
            throw IllegalAccessError("You do not have permission to operate on this vault ($vaultName)")
        }
*/
/*
        if (commitChanges) {
            commit(repo, oldVault, newVault, vaultFiles, validateVersions)
        } else {
            gitService.closeRepository(repo)

        }
*/

        return oldVault!!
    }

    private fun getVaultFiles(repo: Git, vault: String): List<File> {
        return listOf()/*getAllSecretFilesInRepoList(repo)
                .filter { it.path.startsWith("$GIT_SECRET_FOLDER/$vault/") }*/

    }

    private fun commit(repo: Git,
                       oldVault: Vault,
                       vault: Vault,
                       vaultFiles: List<File>,
                       validateVersions: Boolean
    ) {

        if (vault.name.isBlank()) {
            throw IllegalArgumentException("Vault name must be set")
        }
        val vaultPath = ""//""$GIT_SECRET_FOLDER/${vault.name}"

        val secretFilesForVault = vaultFiles.filter { !it.path.endsWith(""/*PERMISSION_FILE*/) }

        val encryptedSecretsFiles: Map<String, String> = encryptSecrets(oldVault.secrets, vault.secrets, secretFilesForVault)

        val result = vault.permissions?.let {
            encryptedSecretsFiles +
                    mapOf(""/*PERMISSION_FILE*/ to jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(it))
        } ?: encryptedSecretsFiles

        if (validateVersions) {
            val invalidVersions = result
                    .filter { oldVault.versions.containsKey(it.key) }
                    .filter {
                        val newVersion = vault.versions[it.key]
                        val oldVersion = oldVault.versions[it.key]
                        newVersion != oldVersion
                    }.map { it.key }

            if (invalidVersions.isNotEmpty()) {
/*

                val errors = invalidVersions.map { fileName ->
                    val commit = vaultFiles.find { it.file.name == fileName }?.commit!!//since oldVersion != null above this is safe
                    VersioningError("$vaultPath/$fileName", commit.authorIdent.name, commit.authorIdent.`when`)
                }
                throw AuroraVersioningException("Source file has changed since you fetched it", errors)
*/
                throw AuroraVersioningException("Source file has changed since you fetched it", listOf())
            }
        }

        val pathResult = result.mapKeys { "$vaultPath/${it.key}" }

        //when we save secret files we do not mess with auroraConfig
        val keep: (String) -> Boolean = { it -> it.startsWith(vaultPath) }
//        gitService.saveFilesAndClose(repo, pathResult, keep)
    }

    private fun encryptSecrets(oldSecrets: Map<String, String>,
                               newSecrets: Map<String, String>,
                               allFilesInRepo: List<File>): Map<String, String> {


        val encryptedChangedSecrets = newSecrets
                .filter { oldSecrets.containsKey(it.key) }
                .filter { it.value != oldSecrets[it.key] }
                .map { it.key to encryptionService.encrypt(it.value) }.toMap()

        val encryptedNewSecrets = newSecrets
                .filter { !oldSecrets.containsKey(it.key) }
                .map { it.key to encryptionService.encrypt(it.value) }.toMap()

        val encryptedSecrets = encryptedNewSecrets + encryptedChangedSecrets

        val deleteKeys = oldSecrets.keys - newSecrets.keys

        val encryptedOldSecrets = allFilesInRepo
                .filter { !encryptedSecrets.containsKey(it.name) }
                .filter { !deleteKeys.contains(it.name) }
                .map { it.name to it.readText() }.toMap()

        return encryptedSecrets + encryptedOldSecrets
    }

}
