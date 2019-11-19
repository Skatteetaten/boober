package no.skatteetaten.aurora.boober.utils

import java.io.File
import org.eclipse.jgit.api.Git

fun recreateRepo(folder: File): Git {
    recreateFolder(folder)
    return Git.init().setDirectory(folder).setBare(true).call()
}
