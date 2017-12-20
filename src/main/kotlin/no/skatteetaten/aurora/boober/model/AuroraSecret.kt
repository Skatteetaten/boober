package no.skatteetaten.aurora.boober.model

import org.eclipse.jgit.revwalk.RevCommit
import org.hibernate.validator.constraints.NotEmpty
import java.io.File

data class AuroraSecretVault @JvmOverloads constructor(
        val name: String,
        val secrets: Map<String, String>,
        val permissions: AuroraPermissions? = null,
        val versions: Map<String, String?> = mapOf()
) {
    init {
        if (name.isBlank()) throw IllegalArgumentException("name must be set")
    }
}

data class AuroraPermissions @JvmOverloads constructor(
        val groups: List<String>? = listOf()
)

data class AuroraSecretFile(
        val path: String,
        val file: File,
        val commit: RevCommit?
)

