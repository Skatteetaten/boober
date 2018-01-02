package no.skatteetaten.aurora.boober.service

import org.eclipse.jgit.api.Git
import java.io.File

fun createInitRepo(affiliation: String) {

    // TODO: Clean up this. We should only use build relative folders.
    recreateRepo(File("/tmp/vaulttest/aos"))
    recreateRepo(File("/tmp/boobertest/aos"))

    recreateRepo(File("build/gitrepos/$affiliation.git"))
    recreateRepo(File("build/vaultrepos/$affiliation.git"))
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
