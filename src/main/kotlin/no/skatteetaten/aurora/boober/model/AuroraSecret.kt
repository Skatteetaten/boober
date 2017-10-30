package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.databind.JsonNode
import org.eclipse.jgit.revwalk.RevCommit
import java.io.File

data class AuroraSecretVault @JvmOverloads constructor(
        val name: String,
        val secrets: Map<String, String>,
        val permissions: AuroraPermissions? = null,
        val versions: Map<String, String?> = mapOf()
)

data class AuroraPermissions @JvmOverloads constructor(
        val groups: List<String>? = listOf(),
        val users: List<String>? = listOf()
//TODO: users is not taken into consideration locally. Remove when client removes it.
)

data class AuroraSecretFile(
        val path: String,
        val file: File,
        val commit: RevCommit?
)

