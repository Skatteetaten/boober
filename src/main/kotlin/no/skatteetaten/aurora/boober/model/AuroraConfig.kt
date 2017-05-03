package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.service.ApplicationId

typealias FileName = String
typealias JsonData = Map<String, Any?>
typealias TextFiles = Map<FileName, String>

data class AuroraConfigFile(val name: FileName, val contents: JsonNode, val override: Boolean = false) {
    val configName
        get() = if (override) "$name.override" else name
}


fun requiredValidator(node: JsonNode?, message: String): Exception? {

    if (node == null) {
        return IllegalArgumentException(message)
    }
    return null
}

fun emptyValidator(node: JsonNode?) = null
data class AuroraConfigExtractor(val name: String,
                                 val path: String, val validator: (JsonNode?) -> Exception? = ::emptyValidator)


fun List<AuroraConfigExtractor>.extractFrom(files: List<AuroraConfigFile>): Map<String, AuroraConfigField> {
    return this.map { (name, path) ->
        files.mapNotNull {
            val value = it.contents.at(path)
            if (value.isMissingNode) {
                null
            } else {
                name to AuroraConfigField(path, value, it.configName)
            }
        }.first()
    }.toMap()
}

data class AuroraConfigField(val path: String, val value: JsonNode, val source: String)


data class AuroraConfig(val auroraConfigFiles: List<AuroraConfigFile>,
                        val secrets: TextFiles = mapOf()) {

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
            throw IllegalArgumentException("Unable to merge files because some required files are missing. Missing ${missingFiles}.")
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

