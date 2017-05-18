package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

typealias FileName = String
typealias JsonData = Map<String, Any?>
typealias TextFiles = Map<FileName, String>

data class AuroraConfig(val auroraConfigFiles: List<AuroraConfigFile>, val secrets: TextFiles = mapOf()) {

    fun getApplicationIds(env: String = "", app: String = ""): List<ApplicationId> {

        return auroraConfigFiles
                .map { it.name.removeSuffix(".json") }
                .filter { it.contains("/") && !it.contains("about") }
                .filter { if (env.isNullOrBlank()) true else it.startsWith(env) }
                .filter { if (app.isNullOrBlank()) true else it.endsWith(app) }
                .map { val (environment, application) = it.split("/"); ApplicationId(environment, application) }
    }

    fun getSecrets(secretFolder: String): Map<String, String> {

        val prefix = if (secretFolder.endsWith("/")) secretFolder else "$secretFolder/"
        return secrets.filter { it.key.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
    }

    fun getFilesForApplication(aid: ApplicationId, overrides: List<AuroraConfigFile> = listOf()): List<AuroraConfigFile> {

        val requiredFilesForApplication = setOf(
                "about.json",
                "${aid.applicationName}.json",
                "${aid.environmentName}/about.json",
                "${aid.environmentName}/${aid.applicationName}.json")

        val filesForApplication = requiredFilesForApplication.mapNotNull { fileName -> auroraConfigFiles.find { it.name == fileName } }
        val overrideFiles = requiredFilesForApplication.mapNotNull { fileName -> overrides.find { it.name == fileName } }
        val allFiles = filesForApplication + overrideFiles

        val uniqueFileNames = HashSet(allFiles.map { it.name })
        if (uniqueFileNames.size != requiredFilesForApplication.size) {
            val missingFiles = requiredFilesForApplication.filter { it !in uniqueFileNames }
            throw IllegalArgumentException("Unable to merge files because some required files are missing. Missing $missingFiles.")
        }

        return allFiles
    }

    fun updateFile(name: String, contents: JsonNode): AuroraConfig {

        val files = auroraConfigFiles.toMutableList()
        val indexOfFileToUpdate = files.indexOfFirst { it.name == name }
        val newAuroraConfigFile = AuroraConfigFile(name, contents)

        if (indexOfFileToUpdate == -1) {
            files.add(newAuroraConfigFile)
        } else {
            files[indexOfFileToUpdate] = newAuroraConfigFile
        }

        return this.copy(auroraConfigFiles = files)
    }

    fun convertFilesToString(mapper: ObjectMapper): Map<String, String> {

        return auroraConfigFiles.map {
            it.name to mapper.writerWithDefaultPrettyPrinter().writeValueAsString(it.contents)
        }.toMap()
    }

    fun convertSecretFilesToString(newSecretPath: String = ""): Map<String, String> {

        return secrets.map {
            val applicationSecretPath = it.key.split("/")
                    .takeIf { it.size >= 2 }
                    ?.let { it.subList(it.size - 2, it.size) }
                    ?.joinToString("/") ?: it.key

            val secretFolder = applicationSecretPath.split("/")[0]
            val gitSecretFolder = "$newSecretPath/$applicationSecretPath".replace("//", "/")

            auroraConfigFiles
                    .filter { it.contents.has("secretFolder") }
                    .filter { it.contents.get("secretFolder").asText().contains(secretFolder) }
                    .forEach {
                        val folder = applicationSecretPath.split("/")[0]
                        (it.contents as ObjectNode).put("secretFolder", "$newSecretPath/$folder")
                    }

            gitSecretFolder to it.value
        }.toMap()
    }
}


data class AuroraConfigFile(val name: FileName, val contents: JsonNode, val override: Boolean = false) {
    val configName
        get() = if (override) "$name.override" else name
}
