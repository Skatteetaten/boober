package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File

data class AuroraConfig(val auroraConfigFiles: List<AuroraConfigFile>, val affiliation: String) {

    companion object {
        @JvmStatic
        fun fromFolder(folderName: String) : AuroraConfig {

            val folder = File(folderName)
            val files = folder.walkBottomUp()
                    .onEnter { !setOf(".secret", ".git").contains(it.name) }
                    .filter { it.isFile }
                    .associate { it.relativeTo(folder).path to it }

            val nodes = files.map {
                it.key to jacksonObjectMapper().readValue(it.value, JsonNode::class.java)
            }.toMap()

            return AuroraConfig(nodes.map { AuroraConfigFile(it.key, it.value!!, false) }, folder.name)
        }
    }

    fun getVersions() = auroraConfigFiles.associate { it.name to it.version }

    fun getApplicationIds(): List<ApplicationId> {

        return auroraConfigFiles

                .map { it.name.removeSuffix(".json") }
                .filter { it.contains("/") && !it.contains("about") && !it.startsWith("templates") }
                .map { val (environment, application) = it.split("/"); ApplicationId(environment, application) }
    }

    @JvmOverloads
    fun getFilesForApplication(applicationId: ApplicationId, overrideFiles: List<AuroraConfigFile> = listOf()): List<AuroraConfigFile> {

        val requiredFiles = requiredFilesForApplication(applicationId)
        val filesForApplication = requiredFiles.mapNotNull { fileName -> auroraConfigFiles.find { it.name == fileName } }

        val overrides = requiredFiles.mapNotNull { fileName -> overrideFiles.find { it.name == fileName } }

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

    private fun getApplicationFile(applicationId: ApplicationId): AuroraConfigFile {
        val fileName = "${applicationId.environment}/${applicationId.application}.json"
        val file = auroraConfigFiles.find { it.name == fileName && !it.override }
        return file ?: throw IllegalArgumentException("Should find applicationFile $fileName")
    }


    private fun requiredFilesForApplication(applicationId: ApplicationId): Set<String> {

        val implementationFile = getApplicationFile(applicationId)
        val baseFile = implementationFile.contents.get("baseFile")?.asText()
                ?: "${applicationId.application}.json"

        val envFile = implementationFile.contents.get("envFile")?.asText()
                ?: "about.json"

        return setOf(
                "about.json",
                baseFile,
                "${applicationId.environment}/$envFile",
                "${applicationId.environment}/${applicationId.application}.json")
    }
}

