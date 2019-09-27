package no.skatteetaten.aurora.boober.model

import com.github.fge.jsonpatch.JsonPatch
import no.skatteetaten.aurora.boober.model.AuroraConfigFileType.APP
import no.skatteetaten.aurora.boober.model.AuroraConfigFileType.BASE
import no.skatteetaten.aurora.boober.model.AuroraConfigFileType.ENV
import no.skatteetaten.aurora.boober.model.AuroraConfigFileType.GLOBAL
import no.skatteetaten.aurora.boober.utils.jacksonYamlObjectMapper
import no.skatteetaten.aurora.boober.utils.jsonMapper
import no.skatteetaten.aurora.boober.utils.removeExtension
import java.io.File
import java.nio.charset.Charset

data class AuroraConfig(val files: List<AuroraConfigFile>, val name: String, val version: String) {

    companion object {

        val yamlMapper = jacksonYamlObjectMapper()
        val jsonMapper = jsonMapper()

        @JvmStatic
        fun fromFolder(folderName: String, version: String): AuroraConfig {

            val folder = File(folderName)
            return fromFolder(folder, version)
        }

        @JvmStatic
        fun fromFolder(folder: File, version: String): AuroraConfig {
            val files = folder.walkBottomUp()
                .onEnter { !setOf(".secret", ".git").contains(it.name) }
                .filter { it.isFile && listOf("json", "yaml").contains(it.extension) }
                .associate { it.relativeTo(folder).path to it }

            val nodes = files.map {
                it.key to it.value.readText(Charset.defaultCharset())
            }.toMap()

            return AuroraConfig(nodes.map { AuroraConfigFile(it.key, it.value, false) }, folder.name, version)
        }
    }

    fun getApplicationDeploymentRefs(): List<ApplicationDeploymentRef> {

        return files
            .map { it.name.removeExtension() }
            .filter { it.contains("/") && !it.contains("about") && !it.startsWith("templates") }
            .map { val (environment, application) = it.split("/"); ApplicationDeploymentRef(environment, application) }
    }

    @JvmOverloads
    fun getFilesForApplication(
        applicationDeploymentRef: ApplicationDeploymentRef,
        overrideFiles: List<AuroraConfigFile> = listOf()
    ): List<AuroraConfigFile> {

        val fileSpec = findFileSpec(applicationDeploymentRef)

        val filesForApplication: Map<AuroraConfigFileSpec, AuroraConfigFile?> = findFiles(fileSpec, files)
        val overrides: List<AuroraConfigFile> = findFiles(fileSpec, overrideFiles).values.filterNotNull().toList()

        val missingFileSpec = filesForApplication.filterValues { it == null }.map { it.key }
        if (missingFileSpec.isNotEmpty()) {
            val missingFiles = missingFileSpec.joinToString(",") {
                "${it.type} file with name ${it.name}"
            }
            throw IllegalArgumentException("Some required AuroraConfig (json|yaml) files missing. $missingFiles.")
        }

        val applicationFiles = filesForApplication.values.filterNotNull().toList()
        return applicationFiles + overrides
    }

    private fun findFiles(
        fileSpec: Set<AuroraConfigFileSpec>,
        files: List<AuroraConfigFile>
    ): Map<AuroraConfigFileSpec, AuroraConfigFile?> {
        return fileSpec.map { spec ->
            spec to files.find { it.name.removeExtension() == spec.name }
        }.toMap()
    }

    fun findFile(filename: String): AuroraConfigFile? = files.find { it.name == filename }

    @JvmOverloads
    fun updateFile(
        name: String,
        contents: String,
        previousVersion: String? = null
    ): Pair<AuroraConfigFile, AuroraConfig> {

        val files = files.toMutableList()
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

        return Pair(newFile, this.copy(files = files))
    }

    fun patchFile(
        filename: String,
        jsonPatchOp: String,
        previousVersion: String? = null
    ): Pair<AuroraConfigFile, AuroraConfig> {

        val patch: JsonPatch = yamlMapper.readValue(jsonPatchOp, JsonPatch::class.java)

        val auroraConfigFile = findFile(filename)
            ?: throw IllegalArgumentException("No such file $filename in AuroraConfig $name")

        val fileContents = patch.apply(auroraConfigFile.asJsonNode)

        val writeMapper = if (filename.endsWith(".yaml")) {
            yamlMapper
        } else jsonMapper

        val rawContents = writeMapper.writerWithDefaultPrettyPrinter().writeValueAsString(fileContents)
        return updateFile(filename, rawContents, previousVersion)
    }

    private fun getApplicationFile(applicationDeploymentRef: ApplicationDeploymentRef): AuroraConfigFile {
        val fileName = "${applicationDeploymentRef.environment}/${applicationDeploymentRef.application}"
        val file = files.find { it.name.removeExtension() == fileName && !it.override }
        return file ?: throw IllegalArgumentException("Should find applicationFile $fileName.(json|yaml)")
    }

    private fun findFileSpec(applicationDeploymentRef: ApplicationDeploymentRef): Set<AuroraConfigFileSpec> {

        val implementationFile = getApplicationFile(applicationDeploymentRef)

        val baseFile = implementationFile.asJsonNode.get("baseFile")?.asText()?.removeExtension()
            ?: applicationDeploymentRef.application

        val envFile = implementationFile.asJsonNode.get("envFile")?.asText()?.removeExtension()
            ?: "about"

        return setOf(
            AuroraConfigFileSpec("about", GLOBAL),
            AuroraConfigFileSpec(baseFile, BASE),
            AuroraConfigFileSpec("${applicationDeploymentRef.environment}/$envFile", ENV),
            AuroraConfigFileSpec("${applicationDeploymentRef.environment}/${applicationDeploymentRef.application}", APP)
        )
    }
}
