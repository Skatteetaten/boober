package no.skatteetaten.aurora.boober.service

import java.io.File

fun recreateFolder(folder: String) = recreateFolder(File(folder))

fun recreateFolder(folder: File): File {
    if (folder.exists()) {
        folder.deleteRecursively()
    }
    folder.mkdirs()
    return folder
}
