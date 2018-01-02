package no.skatteetaten.aurora.boober.service

import org.eclipse.jgit.api.Git
import java.io.File

fun createInitRepo(affiliation: String): Git {

    val testFolder = File("build/gitrepos/$affiliation.git")

    return recreateRepo(testFolder)
}

fun recreateRepo(folder: File): Git {
    recreateFolder(folder)
    return Git.init().setDirectory(folder).setBare(true).call()
}

fun recreateFolder(folder: File) {
    if (folder.exists()) {
        folder.deleteRecursively()
    }
    folder.mkdirs()
}
