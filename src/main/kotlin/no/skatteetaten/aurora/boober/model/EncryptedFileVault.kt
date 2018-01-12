package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.apache.commons.io.FileUtils
import java.io.File
import java.nio.charset.Charset


@JsonIgnoreProperties(ignoreUnknown = true)
data class AuroraPermissions @JvmOverloads constructor(
        val groups: List<String>? = listOf()
)

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

    val vaults: List<EncryptedFileVault>
        get() = findAllVaultFolders().map { EncryptedFileVault.createFromFolder(it, encryptor, decryptor) }

    fun findVaultByName(vaultName: String): EncryptedFileVault? {
        return vaults.firstOrNull { it.name == vaultName }
    }

    fun createVault(vaultName: String): EncryptedFileVault {
        val vaultFolder = File(folder, vaultName)
        return EncryptedFileVault.createFromFolder(vaultFolder, encryptor, decryptor)
    }

    fun deleteVault(vaultName: String) {

        File(folder, vaultName).deleteRecursively()
    }

    private fun findAllVaultFolders(): List<File> {

        return folder.listFiles()
                .filter { it.isDirectory }
                .filter { !it.name.startsWith(".git") }
    }
}

class EncryptedFileVault private constructor(
        val vaultFolder: File,
        private val encryptor: Encryptor,
        private val decryptor: Decryptor
) {
    init {
        if (!vaultFolder.exists()) throw IllegalArgumentException("Must point to an existing folder ${vaultFolder.absoluteFile.absolutePath} does not exist")
    }

    companion object {
        private val PERMISSION_FILE = ".permissions"

        @JvmStatic
        @JvmOverloads
        fun createFromFolder(vaultFolder: File, encryptor: Encryptor = { it }, decryptor: Decryptor = { it }): EncryptedFileVault {

            FileUtils.forceMkdir(vaultFolder)
            return EncryptedFileVault(vaultFolder, encryptor, decryptor)
        }
    }

    val name: String
        get() = vaultFolder.name

    var permissions: List<String>
        get() {
            val permissions: AuroraPermissions? = files
                    .find { it.name == PERMISSION_FILE }
                    ?.let { jacksonObjectMapper().readValue(it) }
            return permissions?.groups ?: emptyList()
        }
        set(value) {
            jacksonObjectMapper().writeValue(File(vaultFolder, PERMISSION_FILE), mapOf(Pair("groups", value)))
        }

    val secrets: Map<String, String>
        get() = files
                .filter { it.name != PERMISSION_FILE }
                .associate { file ->
                    val contents = decryptor(file.readText())
                    file.name to contents
                }.toMap()

    private val files: List<File>
        get() = vaultFolder.listFiles()?.filter { it.isFile } ?: emptyList()

    fun getFile(fileName: String): String {

        return secrets.getOrElse(fileName, { throw IllegalArgumentException("No such file $fileName in vault $name") })
    }

    fun updateFile(fileName: String, fileContents: String) {

        val file = File(vaultFolder, fileName)
        val encryptedContent = encryptor(fileContents)
        FileUtils.write(file, encryptedContent, Charset.defaultCharset())
    }

    fun deleteFile(fileName: String) = FileUtils.deleteQuietly(File(vaultFolder, fileName))

    fun clear() = files.forEach { deleteFile(it.name) }
}