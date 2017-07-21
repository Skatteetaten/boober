package no.skatteetaten.aurora.boober.model

import org.eclipse.jgit.revwalk.RevCommit
import java.io.File

data class AuroraSecretVault @JvmOverloads constructor(
        val name: String,
        val secrets: Map<String, String>,
        val permissions: AuroraPermissions? = null,
        val versions: Map<String, String?> = mapOf(),
        val skipVersionCheck: Boolean = false
)

data class AuroraPermissions(
        val groups: List<String>? = listOf(),
        val users: List<String>? = listOf()
)

data class AuroraGitFile(
        val path: String,
        val file: File,
        val commit: RevCommit?
)