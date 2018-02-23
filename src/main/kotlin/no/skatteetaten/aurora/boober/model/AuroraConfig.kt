package no.skatteetaten.aurora.boober.model

import com.github.fge.jsonpatch.JsonPatch
import no.skatteetaten.aurora.boober.mapper.v1.createAuroraDeploymentSpec
import no.skatteetaten.aurora.boober.utils.jacksonYamlObjectMapper
import no.skatteetaten.aurora.boober.utils.jsonMapper
import no.skatteetaten.aurora.boober.utils.removeExtension
import java.io.File
import java.nio.charset.Charset
import java.util.HashSet

data class AuroraConfig(val auroraConfigFiles: List<AuroraConfigFile>, val affiliation: String) {

    companion object {

        val yamlMapper = jacksonYamlObjectMapper()
        val jsonMapper = jsonMapper()

        @JvmStatic
        fun fromFolder(folderName: String): AuroraConfig {

            val folder = File(folderName)
            return fromFolder(folder)
        }

        @JvmStatic
        fun fromFolder(folder: File): AuroraConfig {
            val files = folder.walkBottomUp()
                .onEnter { !setOf(".secret", ".git").contains(it.name) }
                .filter { it.isFile && listOf("json", "yaml").contains(it.extension) }
                .associate { it.relativeTo(folder).path to it }

            val nodes = files.map {
                it.key to it.value.readText(Charset.defaultCharset())
            }
                .toMap()

            return AuroraConfig(nodes.map { AuroraConfigFile(it.key, it.value!!, false) }, folder.name)
        }
    }

    fun getApplicationIds(): List<ApplicationId> {

        return auroraConfigFiles
            .map { it.name.removeExtension() }
            .filter { it.contains("/") && !it.contains("about") && !it.startsWith("templates") }
            .map { val (environment, application) = it.split("/"); ApplicationId(environment, application) }
    }

    @JvmOverloads
    fun getFilesForApplication(applicationId: ApplicationId, overrideFiles: List<AuroraConfigFile> = listOf()): List<AuroraConfigFile> {

        val requiredFiles = requiredFilesForApplication(applicationId)
        val filesForApplication = requiredFiles.mapNotNull { fileName ->
            auroraConfigFiles.find { it.name.removeExtension() == fileName }
        }

        val overrides = requiredFiles.mapNotNull { fileName ->
            overrideFiles.find { it.name.removeExtension() == fileName }
        }

        val allFiles = filesForApplication + overrides

        val uniqueFileNames = HashSet(allFiles.map { it.name })
        if (uniqueFileNames.size != requiredFiles.size) {
            val missingFiles = requiredFiles.filter { it !in uniqueFileNames }
            val missingFilesWithExtension = missingFiles.map { "$it.(json|yaml)" }
            throw IllegalArgumentException("Unable to merge files because some required files are missing. Missing $missingFilesWithExtension.")
        }

        return allFiles
    }

    fun findFile(filename: String): AuroraConfigFile? = auroraConfigFiles.find { it.name == filename }

    @JvmOverloads
    fun updateFile(name: String, contents: String, previousVersion: String? = null): Pair<AuroraConfigFile, AuroraConfig> {

        val files = auroraConfigFiles.toMutableList()
        val indexOfFileToUpdate = files.indexOfFirst { it.name == name }

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

        val patch: JsonPatch = yamlMapper.readValue(jsonPatchOp, JsonPatch::class.java)

        val auroraConfigFile = findFile(filename)
            ?: throw IllegalArgumentException("No such file $filename in AuroraConfig ${affiliation}")

        val fileContents = patch.apply(auroraConfigFile.asJsonNode)

        val writeMapper = if (filename.endsWith(".yaml")) {
            yamlMapper
        } else jsonMapper

        val rawContents = writeMapper.writerWithDefaultPrettyPrinter()
            .writeValueAsString(fileContents)
        //TODO how do we handle this with regards to yaml/json.
        return updateFile(filename, rawContents, previousVersion)
    }

    @JvmOverloads
    fun getAuroraDeploymentSpec(aid: ApplicationId, overrideFiles: List<AuroraConfigFile> = listOf()): AuroraDeploymentSpec = createAuroraDeploymentSpec(this, aid, overrideFiles = overrideFiles)

    @JvmOverloads
    fun getAllAuroraDeploymentSpecs(overrideFiles: List<AuroraConfigFile> = listOf()): List<AuroraDeploymentSpec> {
        return getApplicationIds().map { createAuroraDeploymentSpec(this, it, overrideFiles) }
    }

    @JvmOverloads
    fun validate(overrideFiles: List<AuroraConfigFile> = listOf()) {
        getAllAuroraDeploymentSpecs(overrideFiles)
    }

    private fun getApplicationFile(applicationId: ApplicationId): AuroraConfigFile {
        val fileName = "${applicationId.environment}/${applicationId.application}"
        val file = auroraConfigFiles.find { it.name.removeExtension() == fileName && !it.override }
        return file ?: throw IllegalArgumentException("Should find applicationFile $fileName.(json|yaml)")
    }

    private fun requiredFilesForApplication(applicationId: ApplicationId): Set<String> {

        val implementationFile = getApplicationFile(applicationId)
        val baseFile = implementationFile.asJsonNode.get("baseFile")?.asText()?.removeExtension()
            ?: applicationId.application

        val envFile = implementationFile.asJsonNode.get("envFile")?.asText()?.removeExtension()
            ?: "about"

        return setOf(
            "about",
            baseFile,
            "${applicationId.environment}/$envFile",
            "${applicationId.environment}/${applicationId.application}")
    }
}

