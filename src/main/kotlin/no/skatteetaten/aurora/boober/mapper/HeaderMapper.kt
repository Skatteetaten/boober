package no.skatteetaten.aurora.boober.mapper

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpecConfigFieldValidator.Companion.namePattern
import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.utils.durationString
import no.skatteetaten.aurora.boober.utils.notBlank
import no.skatteetaten.aurora.boober.utils.oneOf
import no.skatteetaten.aurora.boober.utils.pattern
import no.skatteetaten.aurora.boober.utils.startsWith

val DeploymentConfig.allNonSideCarContainers:List<Container> get() =
    this.spec.template.spec.containers.filter{ !it.name.endsWith("sidecar")}

enum class ApplicationPlatform(val baseImageName: String, val baseImageVersion: Int, val insecurePolicy: String) {
    java("wingnut8", 1, "None"),
    web("wrench8", 1, "Redirect")
}


enum class TemplateType(val versionRequired: Boolean) {
    deploy(true), development(true), localTemplate(false), template(false)
}


val AuroraDeploymentSpec.applicationPlatform: ApplicationPlatform get() = this["applicationPlatform"]


fun generateDeploymentRequest(name: String): JsonNode {

    val deploymentRequest = mapOf(
            "kind" to "DeploymentRequest",
            "apiVersion" to "apps.openshift.io/v1",
            "name" to name,
            "latest" to true,
            "force" to true
    )

    return jacksonObjectMapper().convertValue(deploymentRequest)
}

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
}


data class Permissions(
        val admin: Permission,
        val view: Permission? = null
)

data class Permission(
        val groups: Set<String>?,
        val users: Set<String> = emptySet()
)
