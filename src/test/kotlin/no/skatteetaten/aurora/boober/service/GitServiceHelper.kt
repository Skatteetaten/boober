package no.skatteetaten.aurora.boober.service

import org.eclipse.jgit.api.Git
import java.io.File

fun createInitRepo(affiliation: String): Git {

    val booberTestFolder = File("/tmp/boober-test/$affiliation.git")

    if (!booberTestFolder.exists()) {
        booberTestFolder.mkdirs()
    } else {
        booberTestFolder.deleteRecursively()
        booberTestFolder.mkdirs()
    }

    return Git.init().setDirectory(booberTestFolder).setBare(true).call()
}
