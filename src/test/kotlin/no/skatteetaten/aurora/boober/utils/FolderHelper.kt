package no.skatteetaten.aurora.boober.utils

import java.io.File
import no.skatteetaten.aurora.boober.service.vault.EncryptedFileVault
import no.skatteetaten.aurora.boober.service.vault.VaultWithAccess

fun recreateFolder(folder: File): File {
    if (folder.exists()) {
        folder.deleteRecursively()
    }
    folder.mkdirs()
    return folder
}

fun createTestVault(
    vaultCollectionName: String,
    vaultName: String,
    secretName: String,
    fileContents: String
): VaultWithAccess {
    val folder = recreateFolder(File("build/vaults/$vaultCollectionName/$vaultName"))
    File(folder, secretName).writeText(fileContents)
    return VaultWithAccess(
        vault = EncryptedFileVault.createFromFolder(folder),
        vaultName = vaultName,
        hasAccess = true
    )
}
