package no.skatteetaten.aurora.boober.service

import org.eclipse.jgit.api.Git
import java.io.File

fun createInitRepo(affiliation: String): Git {

    val testFolder = File("build/gitrepos/$affiliation.git")

    recreateFolder(testFolder)

    return Git.init().setDirectory(testFolder).setBare(true).call()
}

fun recreateFolder(folder: File) {
    if (folder.exists()) {
        folder.deleteRecursively()
    }
    folder.mkdirs()
}
