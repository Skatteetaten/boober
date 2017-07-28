package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.databind.JsonNode

data class AuroraConfig(val auroraConfigFiles: List<AuroraConfigFile>, val affiliation: String) {

    fun getVersions() = auroraConfigFiles.associate { it.name to it.version }

    fun getApplicationIds(env: String = "", app: String = ""): List<ApplicationId> {

        return auroraConfigFiles
                .map { it.name.removeSuffix(".json") }
                .filter { it.contains("/") && !it.contains("about") }
                .filter { if (env.isNullOrBlank()) true else it.startsWith(env) }
                .filter { if (app.isNullOrBlank()) true else it.endsWith(app) }
                .map { val (environment, application) = it.split("/"); ApplicationId(environment, application) }
    }

    fun getImplementationFile(applicationId: ApplicationId): AuroraConfigFile? {
        return auroraConfigFiles.find { it.name == "${applicationId.environment}/${applicationId.application}.json" }
    }



    fun requiredFilesForApplication(applicationId: ApplicationId): Set<String> {

        val implementationFile = getImplementationFile(applicationId)
        val baseFile = implementationFile
                ?.contents?.get("baseFile")?.asText()
                ?: "${applicationId.application}.json"

        val envFile = implementationFile
                ?.contents?.get("envFile")?.asText()
                ?: "about.json"

        return setOf(
                "about.json",
                baseFile,
                "${applicationId.environment}/$envFile",
                "${applicationId.environment}/${applicationId.application}.json")
    }

    fun getFilesForApplication(deployCommand: DeployCommand): List<AuroraConfigFile> {


        val requiredFiles = requiredFilesForApplication(deployCommand.applicationId)
        val filesForApplication = requiredFiles.mapNotNull { fileName -> auroraConfigFiles.find { it.name == fileName } }

        val overrides = requiredFiles.mapNotNull { fileName -> deployCommand.overrideFiles.find { it.name == fileName } }

        val allFiles = filesForApplication + overrides

        val uniqueFileNames = HashSet(allFiles.map { it.name })
        if (uniqueFileNames.size != requiredFiles.size) {
            val missingFiles = requiredFiles.filter { it !in uniqueFileNames }
            throw IllegalArgumentException("Unable to merge files because some required files are missing. Missing $missingFiles.")
        }

        return allFiles
    }

    fun updateFile(name: String, contents: JsonNode, configFileVersion: String): AuroraConfig {

        val files = auroraConfigFiles.toMutableList()
        val indexOfFileToUpdate = files.indexOfFirst { it.name == name }
        val newAuroraConfigFile = AuroraConfigFile(name, contents, version = configFileVersion)

        if (indexOfFileToUpdate == -1) {
            files.add(newAuroraConfigFile)
        } else {
            files[indexOfFileToUpdate] = newAuroraConfigFile
        }

        return this.copy(auroraConfigFiles = files)
    }
}

