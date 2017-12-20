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
            val vaultFiles = getAllFiles(folder)
            val vaults: List<AuroraSecretVault> = vaultFiles
                    .groupBy { it.name }
                    .map {
                        val files: List<File> = it.value
                        val name = it.key
                        AuroraSecretVault.createVault(name, files, decryptor)
                    }
            return VaultCollection(folder, vaults)
        }

        private fun getAllFiles(folder: File): List<File> {

            return folder.walkBottomUp()
                    .onEnter { !it.name.startsWith(".git") }
                    .filter { it.isFile }
                    .toList()
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

        fun createVault(name: String, vaultFiles: List<File>, decryptor: Decryptor = { it }): AuroraSecretVault {

            val permissions: AuroraPermissions? = vaultFiles.find { vaultFile ->
                vaultFile.name == PERMISSION_FILE
            }?.let { jacksonObjectMapper().readValue(it) }

            val files = vaultFiles
                    .filter { it.name != PERMISSION_FILE }
                    .associate { gitFile ->
                        val contents = decryptor(gitFile.readText())

                        gitFile.name to contents
                    }.toMap()

            return AuroraSecretVault(name, files, permissions)
        }
    }
}

data class AuroraPermissions @JvmOverloads constructor(
        val groups: List<String>? = listOf()
)