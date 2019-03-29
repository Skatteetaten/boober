package no.skatteetaten.aurora.boober.utils

import java.io.File

fun recreateFolder(folder: File): File {
    if (folder.exists()) {
        folder.deleteRecursively()
    }
    folder.mkdirs()
    return folder
}