package no.skatteetaten.aurora.boober.mapper

import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpecConfigFieldValidator.Companion.namePattern
import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.utils.durationString
import no.skatteetaten.aurora.boober.utils.notBlank
import no.skatteetaten.aurora.boober.utils.oneOf
import no.skatteetaten.aurora.boober.utils.pattern
import no.skatteetaten.aurora.boober.utils.removeExtension
import no.skatteetaten.aurora.boober.utils.startsWith


enum class ApplicationPlatform(val baseImageName: String, val baseImageVersion: Int, val insecurePolicy: String) {
    java("wingnut8", 1, "None"),
    web("wrench8", 1, "Redirect")
}


enum class TemplateType(val versionRequired: Boolean) {
    deploy(true), development(true), localTemplate(false), template(false)
}


val AuroraDeploymentSpec.applicationPlatform: ApplicationPlatform get() = this["applicationPlatform"]

/**
 * The header contains the sources that are required to parse the AuroraConfig files and create a merged file for a
 * particular application. This merged file is then the subject to further parsing and validation and may in it self
 * be invalid.
 */
class HeaderMapper(
        val applicationDeploymentRef: ApplicationDeploymentRef,
        val applicationFiles: List<AuroraConfigFile>
) {

    private val VALID_SCHEMA_VERSIONS = listOf("v1")

    val envNamePattern = "^[a-z0-9\\-]{0,52}$"
    val envNameMessage =
            "Environment must consist of lower case alphanumeric characters or '-'. It must be no longer than 52 characters."

    val handlers = setOf(
            AuroraConfigFieldHandler("schemaVersion", validator = { it.oneOf(VALID_SCHEMA_VERSIONS) }),
            AuroraConfigFieldHandler("type", validator = { node -> node.oneOf(TemplateType.values().map { it.toString() }) }),
            AuroraConfigFieldHandler(
                    "applicationPlatform",
                    defaultValue = "java",
                    validator = { node -> node.oneOf(ApplicationPlatform.values().map { it.toString() }) }),
            AuroraConfigFieldHandler("affiliation", validator = {
                it.pattern(
                        "^[a-z]{1,10}$",
                        "Affiliation can only contain letters and must be no longer than 10 characters"
                )
            }),
            AuroraConfigFieldHandler("segment"),
            AuroraConfigFieldHandler("cluster", validator = { it.notBlank("Cluster must be set") }),
            AuroraConfigFieldHandler("permissions/admin"),
            AuroraConfigFieldHandler("permissions/view"),
            AuroraConfigFieldHandler("permissions/adminServiceAccount"),
            // Max length of OpenShift project names is 63 characters. Project name = affiliation + "-" + envName.
            AuroraConfigFieldHandler(
                    "envName", validator = { it.pattern(envNamePattern, envNameMessage) },
                    defaultSource = "folderName",
                    defaultValue = applicationDeploymentRef.environment
            ),
            AuroraConfigFieldHandler("name",
                    defaultValue = applicationDeploymentRef.application,
                    defaultSource = "fileName",
                    validator = { it.pattern(namePattern, "Name must be alphanumeric and no more than 40 characters", false) }),
            AuroraConfigFieldHandler("env/name", validator = { it.pattern(envNamePattern, envNameMessage, false) }),
            AuroraConfigFieldHandler("env/ttl", validator = { it.durationString() }),
            AuroraConfigFieldHandler("baseFile"),
            AuroraConfigFieldHandler("envFile", validator = {
                it?.startsWith("about-", "envFile must start with about")
            })
    )


    fun extractPermissions(deploymentSpec: AuroraDeploymentSpec): Permissions {

        val viewGroups = deploymentSpec.getDelimitedStringOrArrayAsSet("permissions/view", " ")
        val adminGroups = deploymentSpec.getDelimitedStringOrArrayAsSet("permissions/admin", " ")
        // if sa present add to admin users.
        val adminUsers = deploymentSpec.getDelimitedStringOrArrayAsSet("permissions/adminServiceAccount", " ")

        val adminPermission = Permission(adminGroups, adminUsers)
        val viewPermission = if (viewGroups.isNotEmpty()) Permission(viewGroups) else null

        return Permissions(admin = adminPermission, view = viewPermission)
    }

    fun getApplicationFile(): AuroraConfigFile {
        val fileName = "${applicationDeploymentRef.environment}/${applicationDeploymentRef.application}"
        val file = applicationFiles.find { it.name.removeExtension() == fileName && !it.override }
        return file ?: throw IllegalArgumentException("Should find applicationFile $fileName")
    }
}


data class Permissions(
        val admin: Permission,
        val view: Permission? = null
)

data class Permission(
        val groups: Set<String>?,
        val users: Set<String> = emptySet()
)
