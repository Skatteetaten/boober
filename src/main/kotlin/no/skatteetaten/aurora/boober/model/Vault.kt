package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File

typealias Decryptor = (String) -> String

class VaultCollection private constructor(
        val name: String,
        val path: File,
        val vaults: List<Vault>) {

    companion object {

        fun fromFolder(folder: File, decryptor: Decryptor): VaultCollection {

            val vaultFiles = getAllFiles(folder)
            val vaults: List<Vault> = vaultFiles
                    .groupBy { it.parentFile.name }
                    .map {
                        val files: List<File> = it.value
                        val name = it.key
                        Vault.createFromEncryptedFiles(name, files, decryptor)
                    }
            return VaultCollection(folder.name, folder, vaults)
        }

        private fun getAllFiles(folder: File): List<File> {

            return folder.walkBottomUp()
                    .onEnter { !it.name.startsWith(".git") }
                    .filter { it.isFile() }
                    .toList()
        }
    }

    fun findVaultByName(vaultName: String): Vault? {
        return vaults.firstOrNull { it.name == vaultName }
    }
}

data class Vault @JvmOverloads constructor(
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

        fun createFromEncryptedFiles(name: String, vaultFiles: List<File>, decryptor: Decryptor = { it }): Vault {

            val permissions: AuroraPermissions? = vaultFiles.find { vaultFile ->
                vaultFile.name == PERMISSION_FILE
            }?.let { jacksonObjectMapper().readValue(it) }

            val files = vaultFiles
                    .filter { it.name != PERMISSION_FILE }
                    .associate { gitFile ->
                        val contents = decryptor(gitFile.readText())

                        gitFile.name to contents
                    }.toMap()

            return Vault(name, files, permissions)
        }
    }
}

data class AuroraPermissions @JvmOverloads constructor(
        val groups: List<String>? = listOf()
)