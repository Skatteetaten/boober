package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import no.skatteetaten.aurora.boober.service.AuroraPermissions
import org.apache.commons.io.FileUtils
import java.io.File
import java.nio.charset.Charset

typealias Decryptor = (String) -> String
typealias Encryptor = (String) -> String

class VaultCollection private constructor(
        val folder: File,
        private val encryptor: Encryptor,
        private val decryptor: Decryptor) {

    companion object {
        fun fromFolder(folder: File, encryptor: Encryptor, decryptor: Decryptor): VaultCollection
                = VaultCollection(folder, encryptor, decryptor)
    }

    val name: String
        get() = folder.name

    val vaults: List<Vault>
        get() = findAllVaultFolders().map { Vault.createFromFolder(it, decryptor) }

    fun findVaultByName(vaultName: String): Vault? {
        return vaults.firstOrNull { it.name == vaultName }
    }

    fun createVault(vaultName: String): Vault {
        val vaultFolder = File(folder, vaultName)
        return Vault.createFromFolder(vaultFolder, encryptor, decryptor)
    }

    private fun findAllVaultFolders(): List<File> {

        return folder.listFiles()
                .filter { it.isDirectory }
                .filter { !it.name.startsWith(".git") }
    }
}

class Vault private constructor(
        val vaultFolder: File,
        val encryptor: Encryptor,
        val decryptor: Decryptor
) {
    init {
        if (!vaultFolder.exists()) throw IllegalArgumentException("Must point to an existing folder ${vaultFolder.absoluteFile.absolutePath} does not exist")
    }

    companion object {
        private val PERMISSION_FILE = ".permissions"

        fun createFromFolder(vaultFolder: File, encryptor: Encryptor, decryptor: Decryptor = { it }): Vault {

            FileUtils.forceMkdir(vaultFolder)
            return Vault(vaultFolder, encryptor, decryptor)
        }
    }

    val name: String
        get() = vaultFolder.name

    val permissions: AuroraPermissions?
        get() = vaultFiles
                .find { it.name == PERMISSION_FILE }
                ?.let { jacksonObjectMapper().readValue(it) }

    val vaultFiles: List<File>
        get() = vaultFolder.listFiles().filter { it.isFile }

    val secrets: Map<String, String>
        get() = vaultFiles
                .filter { it.name != PERMISSION_FILE }
                .associate { file ->
                    val contents = decryptor(file.readText())
                    file.name to contents
                }.toMap()

    fun updateFile(fileName: String, fileContents: String) {

        val secretFile = File(vaultFolder, fileName)
        val encryptedContent = encryptor(fileContents)
        FileUtils.write(secretFile, encryptedContent, Charset.defaultCharset())
    }
}