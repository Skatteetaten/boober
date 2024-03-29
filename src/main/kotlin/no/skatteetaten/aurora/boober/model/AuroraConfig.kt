package no.skatteetaten.aurora.boober.model

import java.io.File
import java.nio.charset.Charset
import no.skatteetaten.aurora.boober.feature.HeaderHandlers.Companion.BASE_FILE
import no.skatteetaten.aurora.boober.feature.HeaderHandlers.Companion.ENV_FILE
import no.skatteetaten.aurora.boober.feature.HeaderHandlers.Companion.GLOBAL_FILE
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecValidationException
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.removeExtension

data class AuroraConfig(
    val files: List<AuroraConfigFile>,
    val name: String,
    val ref: String,
    val resolvedRef: String = ref
) {

    companion object {

        fun fromFolder(folderName: String, ref: String, resolvedRef: String): AuroraConfig {

            val folder = File(folderName)
            return fromFolder(folder, ref, resolvedRef)
        }

        fun fromFolder(folder: File, ref: String, resolvedRef: String): AuroraConfig {
            val files = folder.walkBottomUp()
                .onEnter { !setOf(".secret", ".git").contains(it.name) }
                .filter { it.isFile && listOf("json", "yaml").contains(it.extension) }
                .associate { it.relativeTo(folder).path to it }

            val nodes = files.map {
                it.key to it.value.readText(Charset.defaultCharset())
            }.toMap()

            return AuroraConfig(nodes.map { AuroraConfigFile(it.key, it.value, false) }, folder.name, ref, resolvedRef)
        }
    }

    private fun findDuplicateFiles(): Map<String, List<String>> {
        return files.groupBy { it.name.removeExtension() }.filter { it.value.size > 1 }
            .mapValues { it.value.map { it.name } }
    }

    fun getApplicationDeploymentRefs(): List<ApplicationDeploymentRef> {

        // ensure that the filename is not duplicated
        val duplicateFiles = findDuplicateFiles()
        if (duplicateFiles.isNotEmpty()) {
            val errorMessages: List<String> =
                duplicateFiles.map { it.value.joinToString(separator = ", ", prefix = "[", postfix = "]") }
            val errorMessage = errorMessages.joinToString(" and ")
            throw AuroraDeploymentSpecValidationException("The following files are ambigious $errorMessage")
        }

        return files
            .map { it.name.removeExtension() }
            .filter { it.contains("/") && !it.contains("about") && !it.startsWith("templates") }
            .map { val (environment, application) = it.split("/"); ApplicationDeploymentRef(environment, application) }
    }

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
            spec to files.find { it.name.removeExtension() == spec.name }?.let {
                it.copy(typeHint = spec.type)
            }
        }.toMap()
    }

    fun updateFile(
        name: String,
        contents: String,
        previousVersion: String? = null
    ): Pair<AuroraConfigFile, AuroraConfig> {

        val indexedValue = files.withIndex().find {
            it.value.name == name
        }

        if (indexedValue == null) {
            if (previousVersion != null) {
                throw PreconditionFailureException("The fileName=$name does not exist with a version of ($previousVersion).")
            }
        } else {
            if (previousVersion == null) {
                throw PreconditionFailureException("The fileName=$name already exists in this AuroraConfig.")
            }

            if (indexedValue.value.version != previousVersion) {
                throw AuroraVersioningException(this, indexedValue.value, previousVersion)
            }
        }

        val files = files.toMutableList()

        val newFile = AuroraConfigFile(name, contents)
        if (indexedValue == null) {
            files.add(newFile)
        } else {
            files[indexedValue.index] = newFile
        }

        return Pair(newFile, this.copy(files = files))
    }

    fun getApplicationFile(applicationDeploymentRef: ApplicationDeploymentRef): AuroraConfigFile {
        val fileName = "${applicationDeploymentRef.environment}/${applicationDeploymentRef.application}"
        val file = files.find { it.name.removeExtension() == fileName && !it.override }
        return file ?: throw IllegalArgumentException("Should find applicationFile $fileName.(json|yaml)")
    }

    private fun findFileSpec(applicationDeploymentRef: ApplicationDeploymentRef): Set<AuroraConfigFileSpec> {

        val implementationFile = getApplicationFile(applicationDeploymentRef)

        val baseFile = implementationFile.asJsonNode.get(BASE_FILE)?.asText()?.removeExtension()
            ?: applicationDeploymentRef.application

        val envFile = implementationFile.asJsonNode.get(ENV_FILE)?.asText()?.removeExtension()
            ?: "about"

        require(envFile.startsWith("about")) { "envFile must start with about" }

        val envFileJson = files.find { file ->
            file.name.startsWith(applicationDeploymentRef.environment) &&
                file.name.removePrefix("${applicationDeploymentRef.environment}/").removeExtension() == envFile &&
                !file.override
        }?.asJsonNode
            ?: throw IllegalArgumentException("EnvFile $envFile.(json|yaml) missing for application: $applicationDeploymentRef")

        val include = envFileJson.get("includeEnvFile")?.asText()

        val envFiles = include?.let {
            require(it.substringAfterLast("/").startsWith(("about"))) { "included envFile must start with about" }
            AuroraConfigFileSpec(it.removeExtension(), AuroraConfigFileType.INCLUDE_ENV)
        }
        val baseFileJson = files.find { file ->
            file.name.removeExtension() == baseFile && !file.override
        }?.asJsonNode
            ?: throw IllegalArgumentException("BaseFile $baseFile.(json|yaml) missing for application: $applicationDeploymentRef")

        val globalFile = envFileJson.get(GLOBAL_FILE)?.asText()?.removeExtension()
            ?: baseFileJson.get(GLOBAL_FILE)?.asText()?.removeExtension() ?: "about"

        return setOf(
            AuroraConfigFileSpec(globalFile, AuroraConfigFileType.GLOBAL),
            AuroraConfigFileSpec(baseFile, AuroraConfigFileType.BASE)
        ).addIfNotNull(envFiles)
            .addIfNotNull(
                setOf(
                    AuroraConfigFileSpec("${applicationDeploymentRef.environment}/$envFile", AuroraConfigFileType.ENV),
                    AuroraConfigFileSpec(
                        "${applicationDeploymentRef.environment}/${applicationDeploymentRef.application}",
                        AuroraConfigFileType.APP
                    )
                )
            )
    }

    fun merge(localAuroraConfig: AuroraConfig): AuroraConfig {

        if (localAuroraConfig.files.isEmpty()) {
            return this
        }

        val newVersion = "${this.ref}.dirty"

        val newFileNames = localAuroraConfig.files.map { it.name }.toSet()

        val notInLocal = this.files.filterNot { newFileNames.contains(it.name) }

        val files = localAuroraConfig.files + notInLocal

        return AuroraConfig(files, localAuroraConfig.name, newVersion, newVersion)
    }
}
