package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.databind.JsonNode

data class AuroraConfig(val auroraConfigFiles: List<AuroraConfigFile>, val secrets: Map<String, String> = mapOf()) {

    val overrides: MutableList<AuroraConfigFile> = mutableListOf()

    fun addOverrides(overrides: List<AuroraConfigFile>) {
        this.overrides.addAll(overrides)
    }

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

    fun getFilesForApplication(aid: ApplicationId): List<AuroraConfigFile> {

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
}
