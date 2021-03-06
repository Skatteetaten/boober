package no.skatteetaten.aurora.boober.utils

import org.eclipse.jgit.api.Git
import java.io.File

fun recreateRepo(folder: File): Git {
    recreateFolder(folder)
    return Git.init().setDirectory(folder).setBare(true).call()
}
