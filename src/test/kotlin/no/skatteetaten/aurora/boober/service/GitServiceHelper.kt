package no.skatteetaten.aurora.boober.service

import org.eclipse.jgit.api.Git
import java.io.File

fun createInitRepo(affiliation: String): Git {

    val testFolder = File("build/gitrepos/$affiliation.git")

    if (!testFolder.exists()) {
        testFolder.mkdirs()
    } else {
        testFolder.deleteRecursively()
        testFolder.mkdirs()
    }

    return Git.init().setDirectory(testFolder).setBare(true).call()
}
