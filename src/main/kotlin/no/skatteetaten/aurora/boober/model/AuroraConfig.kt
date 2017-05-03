package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.TextNode
import no.skatteetaten.aurora.boober.service.ApplicationId
import org.slf4j.Logger
import org.slf4j.LoggerFactory

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
data class AuroraConfigFieldHandler(val name: String,
                                    val path: String = "/$name",
                                    val validator: (JsonNode?) -> Exception? = ::emptyValidator,
                                    val defultValue: String? = null)


fun List<AuroraConfigFieldHandler>.extractFrom(files: List<AuroraConfigFile>): Map<String, AuroraConfigField> {

    val logger: Logger = LoggerFactory.getLogger(AuroraConfig::class.java)
    return this.mapNotNull { handler ->
        val matches = files.mapNotNull {
            logger.debug("Sjekker om ${handler.path} finnes i fil ${it.contents}")
            val value = it.contents.at(handler.path)
            if (value.isMissingNode) {
                null
            } else {
                logger.debug("Match ${value} i fil ${it.configName}")

                handler.name to AuroraConfigField(handler.path, value, it.configName)
            }
        }
        if (matches.isEmpty() && handler.defultValue != null) {
            logger.debug("Default match")
            handler.name to AuroraConfigField(handler.path, TextNode(handler.defultValue), "default")
        } else if (matches.isEmpty()) {
            null
        } else {
            matches.first()
        }
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

