package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.fge.jsonpatch.JsonPatch
import no.skatteetaten.aurora.boober.mapper.v1.createAuroraDeploymentSpec
import java.io.File
import java.util.*

data class AuroraConfig(val auroraConfigFiles: List<AuroraConfigFile>, val affiliation: String) {

    companion object {
        @JvmStatic
        fun fromFolder(folderName: String): AuroraConfig {

            val folder = File(folderName)
            return fromFolder(folder)
        }

        @JvmStatic
        fun fromFolder(folder: File): AuroraConfig {
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

    fun findFile(filename: String): AuroraConfigFile? = auroraConfigFiles.find { it.name == filename }

    @JvmOverloads
    fun updateFile(name: String, contents: JsonNode, previousVersion: String? = null): Pair<AuroraConfigFile, AuroraConfig> {

        val files = auroraConfigFiles.toMutableList()
        val indexOfFileToUpdate = files.indexOfFirst { it.name == name }
        if (indexOfFileToUpdate == -1) throw IllegalArgumentException("No such file $name in AuroraConfig $affiliation")
        val newFile = AuroraConfigFile(name, contents)

        if (indexOfFileToUpdate == -1) {
            files.add(newFile)
        } else {
            val currentFile = files[indexOfFileToUpdate]
            if (previousVersion != null && currentFile.version != previousVersion) {
                throw AuroraVersioningException(this, currentFile, previousVersion)
            }
            files[indexOfFileToUpdate] = newFile
        }

        return Pair(newFile, this.copy(auroraConfigFiles = files))
    }

    fun patchFile(filename: String, jsonPatchOp: String, previousVersion: String? = null): Pair<AuroraConfigFile, AuroraConfig> {

        val mapper = jacksonObjectMapper()
        val patch: JsonPatch = mapper.readValue(jsonPatchOp, JsonPatch::class.java)

        val auroraConfigFile = findFile(filename)
                ?: throw IllegalArgumentException("No such file $filename in AuroraConfig ${affiliation}")
        val originalContentsNode = mapper.convertValue(auroraConfigFile.contents, JsonNode::class.java)

        val fileContents = patch.apply(originalContentsNode)
        return updateFile(filename, fileContents, previousVersion)
    }

    @JvmOverloads
    fun getAuroraDeploymentSpec(aid: ApplicationId, overrideFiles: List<AuroraConfigFile> = listOf()): AuroraDeploymentSpec
            = createAuroraDeploymentSpec(this, aid, overrideFiles = overrideFiles)

    @JvmOverloads
    fun getAllAuroraDeploymentSpecs(overrideFiles: List<AuroraConfigFile> = listOf()): List<AuroraDeploymentSpec> {
        return getApplicationIds().map { createAuroraDeploymentSpec(this, it, overrideFiles) }
    }

    @JvmOverloads
    fun validate(overrideFiles: List<AuroraConfigFile> = listOf()) {
        getAllAuroraDeploymentSpecs(overrideFiles)
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

