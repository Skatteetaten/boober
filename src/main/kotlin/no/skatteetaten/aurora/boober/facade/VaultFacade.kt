package no.skatteetaten.aurora.boober.facade

import com.fasterxml.jackson.databind.ObjectMapper
import no.skatteetaten.aurora.boober.model.AuroraSecretFile
import no.skatteetaten.aurora.boober.model.AuroraSecretVault
import no.skatteetaten.aurora.boober.model.AuroraSecretVaultPayload
import no.skatteetaten.aurora.boober.model.VersioningError
import no.skatteetaten.aurora.boober.service.AuroraVersioningException
import no.skatteetaten.aurora.boober.service.EncryptionService
import no.skatteetaten.aurora.boober.service.GitService
import no.skatteetaten.aurora.boober.service.SecretVaultPermissionService
import no.skatteetaten.aurora.boober.service.SecretVaultService
import org.eclipse.jgit.api.Git
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class VaultFacade(
        val gitService: GitService,
        val mapper: ObjectMapper,
        val encryptionService: EncryptionService,
        val secretVaultService: SecretVaultService,
        val secretVaultPermissionService: SecretVaultPermissionService
) {

    private val logger = LoggerFactory.getLogger(VaultFacade::class.java)
    private val GIT_SECRET_FOLDER = ".secret"
    private val PERMISSION_FILE = ".permissions"


    fun listAllVaultsWithUserAccess(affiliation: String): List<AuroraSecretVaultPayload> {

        val repo = getRepo(affiliation)

        val vaults = secretVaultService.getVaults(repo)
                .values
                .map { AuroraSecretVaultPayload(it.name, it.secrets.values.toList(), it.permissions, secretVaultPermissionService.hasUserAccess(it.permissions)) }
                .toList()
        gitService.closeRepository(repo)
        return vaults
    }

    fun listAllVaults(git: Git): List<AuroraSecretVault> {
        return secretVaultService.getVaults(git)
                .values.toList()
    }


    fun find(affiliation: String, vault: String): AuroraSecretVault {

        return withAuroraVault(affiliation, vault, false, false)
    }

    fun save(affiliation: String, vault: AuroraSecretVault, validateVersions: Boolean): AuroraSecretVault {
        return withAuroraVault(affiliation, vault.name, validateVersions, function = {
            vault
        })
    }

    fun delete(affiliation: String, vault: String): Boolean {
        val repo = getRepo(affiliation)

        gitService.deleteDirectory(repo, "$GIT_SECRET_FOLDER/$vault/")
        return true
    }

    fun updateSecretFile(affiliation: String,
                         vault: String,
                         fileName: String,
                         fileContents: String,
                         fileVersion: String,
                         validateVersions: Boolean): AuroraSecretVault {

        return withAuroraVault(affiliation, vault, validateVersions, function = {
            val newVersions = it.versions + (fileName to fileVersion)
            val newSecrets = it.secrets + (fileName to fileContents)
            it.copy(versions = newVersions, secrets = newSecrets)
        })

    }

    private fun withAuroraVault(affiliation: String,
                                vault: String,
                                validateVersions: Boolean,
                                commitChanges: Boolean = true,
                                function: (AuroraSecretVault) -> AuroraSecretVault = { it -> it }): AuroraSecretVault {

        val repo = getRepo(affiliation)

        val vaultFiles = secretVaultService.getVaultFiles(repo, vault)

        val oldVault = secretVaultService.createVault(vault, vaultFiles)

        if (!secretVaultPermissionService.hasUserAccess(oldVault.permissions)) {
            throw IllegalAccessError("You do not have permission to operate on his vault")
        }

        val newSecrets = function(oldVault)
        if (!secretVaultPermissionService.hasUserAccess(newSecrets.permissions)) {
            throw IllegalAccessError("You do not have permission to operate on his vault")
        }
        if (commitChanges) {
            commit(repo, oldVault, newSecrets, vaultFiles, validateVersions)
        } else {
            gitService.closeRepository(repo)

        }

        return newSecrets
    }


    private fun getRepo(affiliation: String): Git {
        return gitService.checkoutRepoForAffiliation(affiliation)
    }


    private fun commit(repo: Git,
                       oldVault: AuroraSecretVault,
                       vault: AuroraSecretVault,
                       vaultFiles: List<AuroraSecretFile>,
                       validateVersions: Boolean
    ) {


        val vaultPath = "$GIT_SECRET_FOLDER/${vault.name}"

        val secretFilesForVault = vaultFiles.filter { !it.path.endsWith(PERMISSION_FILE) }

        val encryptedSecretsFiles: Map<String, String> = encryptSecrets(oldVault.secrets, vault.secrets, secretFilesForVault)

        val result = vault.permissions?.let {
            encryptedSecretsFiles +
                    mapOf(PERMISSION_FILE to mapper.writerWithDefaultPrettyPrinter().writeValueAsString(it))
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

                val errors = invalidVersions.map { fileName ->
                    val commit = vaultFiles.find { it.file.name == fileName }?.commit!!//since oldVersion != null above this is safe
                    VersioningError("$vaultPath/$fileName", commit.authorIdent.name, commit.authorIdent.`when`)
                }
                throw AuroraVersioningException("Source file has changed since you fetched it", errors)
            }
        }

        val pathResult = result.mapKeys { "$vaultPath/${it.key}" }

        //when we save secret files we do not mess with auroraConfig
        val keep: (String) -> Boolean = { it -> it.startsWith(vaultPath) }
        gitService.saveFilesAndClose(repo, pathResult, keep)
    }

    private fun encryptSecrets(oldSecrets: Map<String, String>,
                               newSecrets: Map<String, String>,
                               allFilesInRepo: List<AuroraSecretFile>): Map<String, String> {


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
                .filter { !encryptedSecrets.containsKey(it.file.name) }
                .filter { !deleteKeys.contains(it.file.name) }
                .map { it.file.name to it.file.readText() }.toMap()

        return encryptedSecrets + encryptedOldSecrets
    }
}
