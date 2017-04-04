package no.skatteetaten.aurora.boober.model

import no.skatteetaten.aurora.boober.service.ValidationException
import no.skatteetaten.aurora.boober.service.m
import no.skatteetaten.aurora.boober.service.s
import no.skatteetaten.aurora.boober.utils.createMergeCopy
import javax.validation.Validation
import javax.validation.constraints.NotNull
import javax.validation.constraints.Pattern
import javax.validation.constraints.Size

class AuroraConfig(
        val app: String = "",
        val env: String,
        val aocConfigFiles: Map<String, Map<String, Any?>>
) {

    val isSingleApplication: Boolean
        get() = !app.isNullOrBlank()

    fun applicationsToDeploy(): List<Pair<String, String>> {
        return if (isSingleApplication) listOf(Pair(env, app))
        else aocConfigFiles.entries
                .filter { !it.key.contains("about.json") && !it.key.contains("/") }
                .map { Pair(env, it.key.removeSuffix(".json")) }
    }


    fun getMergedFileForApplication(environmentName: String, applicationName: String): Map<String, Any?> {
        val filesForApplication = getFilesForApplication(environmentName, applicationName)
        val mergedJson = mergeAocConfigFiles(filesForApplication)

        mergedJson.apply {
            putIfAbsent("envName", environmentName)
            putIfAbsent("schemaVersion", "v1")
        }

        assertIsValid(mergedJson, applicationName)

        return mergedJson
    }

    fun getFilesForApplication(environmentName: String, applicationName: String): List<Map<String, Any?>> {

        val requiredFilesForApplication = setOf(
                "about.json",
                "$applicationName.json",
                "$environmentName/about.json",
                "$environmentName/$applicationName.json")

        val filesForApplication: List<Map<String, Any?>> = requiredFilesForApplication.mapNotNull { aocConfigFiles[it] }
        if (filesForApplication.size != requiredFilesForApplication.size) {
            val missingFiles = requiredFilesForApplication.filter { it !in aocConfigFiles.keys }
            throw IllegalArgumentException("Unable to execute setup command. Required files missing => $missingFiles")
        }
        return filesForApplication
    }

    private fun mergeAocConfigFiles(filesForApplication: List<Map<String, Any?>>): MutableMap<String, Any?> {

        return filesForApplication.reduce(::createMergeCopy).toMutableMap()
    }


    internal fun assertIsValid(mergedJson: Map<String, Any?>?, applicationName: String) {
        val validator = Validation.buildDefaultValidatorFactory().validator

        val config = AuroraConfigRequiredV1(mergedJson, mergedJson?.m("build"))
        val auroraDcErrors = validator.validate(config)

        val errors = auroraDcErrors.map { "${it.propertyPath}: ${it.message}" }

        if (errors.isNotEmpty()) {
            throw ValidationException("Config for application '$applicationName' contains errors", errors = errors)
        }
        //TODO:validate that all users/groups are actually valid groups/users
/*
        if (config is TemplateProcessingConfig) {

            if (config.templateFile != null && !res.sources.keys.contains(config.templateFile)) {
                errors.add("Template file ${config.templateFile} is missing in sources")
            }

            if (config.template != null && config.templateFile != null) {
                errors.add("Cannot specify both template and templateFile")
            }

            if (config.template != null && !openShiftService.templateExist(token, config.template)) {
                errors.add("Template ${config.template} does not exist in cluster.")
            }
*/
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

    @get:Pattern(message = "Alphanumeric and dashes. Cannot end or start with dash", regexp = "^[a-z0-9][-a-z0-9]*[a-z0-9]$")
    val namespace = "$affiliation-$envName"
}
