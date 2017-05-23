package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler

data class AuroraConfig(
        val auroraConfigFiles: List<AuroraConfigFile>,
        val secrets: Map<String, String> = mapOf(),
        val overrides: MutableList<AuroraConfigFile> = mutableListOf()
) {

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

    fun findConfigFieldHandlers(aid: ApplicationId): List<AuroraConfigFieldHandler> {

        val name = "config"
        val configFiles = findSubKeys(aid, name)

        val configKeys: Map<String, Set<String>> = configFiles.map { configFileName ->
            //find all unique keys in a configFile
            val keys = getFilesForApplication(aid).flatMap { ac ->
                ac.contents.at("/$name/$configFileName")?.fieldNames()?.asSequence()?.toList() ?: emptyList()
            }.toSet()

            configFileName to keys
        }.toMap()

        val result = configKeys.flatMap { configFile ->
            configFile.value.map { field ->
                AuroraConfigFieldHandler("$name/${configFile.key}/$field")
            }
        }

        return result
    }

    fun findParametersFieldHandlers(aid: ApplicationId): List<AuroraConfigFieldHandler> {

        val parameterKeys = findSubKeys(aid, "parameters")

        val result = parameterKeys.map { parameter ->
            AuroraConfigFieldHandler("parameters/$parameter")
        }

        return result
    }

    private fun findSubKeys(aid: ApplicationId, name: String): Set<String> {
        return getFilesForApplication(aid).flatMap {
            if (it.contents.has(name)) {
                it.contents[name].fieldNames().asSequence().toList()
            } else {
                emptyList()
            }
        }.toSet()
    }
}
