package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.service.ApplicationConfigException
import no.skatteetaten.aurora.boober.service.ApplicationId
import no.skatteetaten.aurora.boober.service.m
import no.skatteetaten.aurora.boober.service.s
import no.skatteetaten.aurora.boober.utils.createMergeCopy
import javax.validation.Validation
import javax.validation.constraints.NotNull
import javax.validation.constraints.Pattern
import javax.validation.constraints.Size

typealias FileName = String
typealias JsonData = Map<String, Any?>
typealias TextFiles = Map<FileName, String>

data class AuroraConfigFile(val name: FileName, val contents: JsonData, val override: Boolean = false) {
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
data class AuroraConfigExtractor(val path: String,
                                 val validator: (JsonNode?) -> Exception? = ::emptyValidator)

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


    fun getMergedFileForApplication(aid: ApplicationId, overrides: List<AuroraConfigFile>): Map<String, Any?> {

        val allFiles = getFilesForApplication(aid, overrides)

        val mergedJson = mergeAocConfigFiles(allFiles.map { it.contents })

        mergedJson.apply {
            putIfAbsent("envName", aid.environmentName)
            putIfAbsent("schemaVersion", "v1")
        }

        validate(mergedJson).takeIf { it.isNotEmpty() }?.let {
            throw ApplicationConfigException("Config for application ${aid.applicationName} in environment ${aid.environmentName} contains errors", errors = it)
        }

        return mergedJson
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

    fun updateFile(name: String, contents: Map<String, Any?>): AuroraConfig {
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

    private fun mergeAocConfigFiles(filesForApplication: List<Map<String, Any?>>): MutableMap<String, Any?> {

        return filesForApplication.reduce(::createMergeCopy).toMutableMap()
    }

    internal fun validate(mergedJson: Map<String, Any?>): List<String> {
        val validator = Validation.buildDefaultValidatorFactory().validator

        val config = AuroraConfigRequiredV1(mergedJson, mergedJson.m("build"))
        val auroraDcErrors = validator.validate(config)

        val errors = mutableListOf<String>()
        errors.addAll(auroraDcErrors.map { "Illegal value for property ${it.propertyPath}: ${it.message}" })

        mergedJson.s("secretFolder")?.let {
            val secrets = getSecrets(it)
            // TODO: More validation needed here
            if (secrets.isEmpty()) {
                errors.add("No secret files with prefix $it")
            }
        }
        return errors
    }
}

class AuroraConfigRequiredV1(val config: Map<String, Any?>?, val build: Map<String, Any?>?) {

    @get:NotNull
    @get:Pattern(message = "Only lowercase letters, max 24 length", regexp = "^[a-z]{0,23}[a-z]$")
    val affiliation
        get() = config?.s("affiliation")

    @get:NotNull
    val cluster
        get() = config?.s("cluster")

    @get:NotNull
    val type
        get() = config?.s("type")?.let { TemplateType.valueOf(it) }

    @get:Pattern(message = "Must be valid DNSDNS952 label", regexp = "^[a-z][-a-z0-9]{0,23}[a-z0-9]$")
    val name
        get() = config?.s("name") ?: build?.s("ARTIFACT_ID")

    val envName
        get() = config?.s("envName")

    @get:NotNull
    @get:Size(min = 1, max = 50)
    val artifactId = build?.s("ARTIFACT_ID")

    @get:NotNull
    @get:Size(min = 1, max = 200)
    val groupId = build?.s("GROUP_ID")

    @get:NotNull
    @get:Size(min = 1)
    val version = build?.s("VERSION")
}
