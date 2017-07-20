package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import no.skatteetaten.aurora.boober.model.AuroraGitFile
import no.skatteetaten.aurora.boober.model.AuroraPermissions
import no.skatteetaten.aurora.boober.model.AuroraSecretVault
import org.eclipse.jgit.api.Git
import org.springframework.stereotype.Service

@Service
class SecretVaultService(val mapper: ObjectMapper,
                         val encryptionService: EncryptionService,
                         val gitService: GitService) {


    private val PERMISSION_FILE = ".permissions"
    private val GIT_SECRET_FOLDER = ".secret"


    fun getVaults(repo: Git): Map<String, AuroraSecretVault> {

        val vaultFiles: List<AuroraGitFile> = gitService.getAllFilesInRepoList(repo)
                .filter { it.path.startsWith(GIT_SECRET_FOLDER) }


        return vaultFiles
                .groupBy { it.path.split("/")[1] } //.secret/<vaultName>/<secretName>
                .mapValues { createVault(it.key, it.value) }

    }

    fun getVaultFiles(repo: Git, vault: String): List<AuroraGitFile> {
        return gitService.getAllFilesInRepoList(repo)
                .filter { it.path.startsWith("$GIT_SECRET_FOLDER/$vault") }

    }

    fun createVault(name: String, vaultFiles: List<AuroraGitFile>): AuroraSecretVault {

        val permissions: AuroraPermissions? = vaultFiles.find { gitFile -> gitFile.file.name == PERMISSION_FILE }
                ?.file
                ?.let { mapper.readValue(it) }

        val files = vaultFiles.filter { it.file.name != ".permissions" }.associate { gitFile ->
            val contents = encryptionService.decrypt(gitFile.file.readText())

            gitFile.file.name to contents
        }.toMap()

        val versions = vaultFiles.associate { it.file.name to it.commit?.abbreviate(7)?.name() }

        return AuroraSecretVault(name, files, permissions, versions)

    }
}