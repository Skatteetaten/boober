package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File

typealias Decryptor = (String) -> String

class VaultCollection private constructor(
        val vaultPath: File,
        val vaults: List<AuroraSecretVault>) {

    companion object {

        fun fromFolder(folder: File, decryptor: Decryptor): VaultCollection {
            val vaultFiles: List<AuroraSecretFile> = findAllSecretFiles(folder)
            val vaults: List<AuroraSecretVault> = vaultFiles
                    .groupBy { it.vaultName }
                    .map {
                        val vaultName: String = it.key
                        val files: List<AuroraSecretFile> = it.value
                        AuroraSecretVault.createVault(vaultName, files, decryptor)
                    }
            return VaultCollection(folder, vaults)
        }

        private fun findAllSecretFiles(folder: File): List<AuroraSecretFile> {

            return getAllFiles(folder)
                    .map {
                        AuroraSecretFile(it.key, it.value)
                    }
        }

        private fun getAllFiles(folder: File): Map<String, File> {

            return folder.walkBottomUp()
                    .onEnter { !it.name.startsWith(".git") }
                    .filter { it.isFile }
                    .associate {
                        it.relativeTo(folder).path to it
                    }
        }
    }
}

data class AuroraSecretVaultWithAccess @JvmOverloads constructor(
        val secretVault: AuroraSecretVault,
        val hasAccess: Boolean = true
)

data class AuroraSecretVault @JvmOverloads constructor(
        val name: String,
        val secrets: Map<String, String>,
        val permissions: AuroraPermissions? = null,
        val versions: Map<String, String?> = mapOf()
) {
    init {
        if (name.isBlank()) throw IllegalArgumentException("name must be set")
    }

    companion object {
        private val PERMISSION_FILE = ".permissions"

        fun createVault(name: String, vaultFiles: List<AuroraSecretFile>, decryptor: Decryptor = { it }): AuroraSecretVault {

            val permissions: AuroraPermissions? = vaultFiles.find { vaultFile ->
                vaultFile.file.name == PERMISSION_FILE
            }?.file?.let { jacksonObjectMapper().readValue(it) }

            val files = vaultFiles
                    .filter { it.file.name != PERMISSION_FILE }
                    .associate { gitFile ->
                        val contents = decryptor(gitFile.file.readText())

                        gitFile.file.name to contents
                    }.toMap()

            return AuroraSecretVault(name, files, permissions)
        }
    }
}

data class AuroraPermissions @JvmOverloads constructor(
        val groups: List<String>? = listOf()
)

data class AuroraSecretFile(
        val path: String,
        val file: File
//        val commit: RevCommit?
) {
    // path = .secret/<vaultName>/<secretName>
    val vaultName: String
        get() = path.split("/")[1]

    val secretName: String
        get() = path.split("/")[2]
}

