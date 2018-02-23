package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.databind.JsonNode

import no.skatteetaten.aurora.boober.utils.ensureEndsWith

enum class TemplateType {
    deploy, development, localTemplate, template, build
}

data class AuroraDeployEnvironment(
    val affiliation: String,
    val envName: String,
    val permissions: Permissions
) {
    val namespace: String
        get() = when {
            envName.isBlank() -> affiliation
            envName.startsWith("-") -> "${affiliation}${envName}"
            else -> "$affiliation-$envName"
        }
}

data class AuroraDeploymentSpec(
    val schemaVersion: String,
    val type: TemplateType,
    val name: String,
    val fields: Map<String, Map<String, Any?>>,
    val applicationPlatform: String = "java",
    val cluster: String,
    val environment: AuroraDeployEnvironment,
    val volume: AuroraVolume? = null,
    val route: AuroraRoute? = null,
    val build: AuroraBuild? = null,
    val deploy: AuroraDeploy? = null,
    val template: AuroraTemplate? = null,
    val localTemplate: AuroraLocalTemplate? = null
) {

    fun assembleRouteHost(hostPrefix: String = name): String {

        val hostSuffix = "${environment.namespace}.${cluster}.paas.skead.no"

        return if (hostPrefix.isBlank()) {
            hostSuffix
        } else {
            hostPrefix.ensureEndsWith(hostSuffix, "-")
        }
    }
}

data class AuroraVolume(
    val secretVaultName: String?,
    val secretVaultKeys: List<String>,
    val config: Map<String, Any?>?,
    val mounts: List<Mount>?
)

data class AuroraRoute(
    val route: List<Route>
)

data class AuroraBuild(
    val baseName: String,
    val baseVersion: String,
    val builderName: String,
    val builderVersion: String,
    val testGitUrl: String?,
    val testTag: String?,
    val extraTags: String,
    val groupId: String,
    val artifactId: String,
    val version: String,
    val outputKind: String,
    val outputName: String,
    val triggers: Boolean,
    val buildSuffix: String?,
    val applicationPlatform: String
)

data class AuroraDeploy(
    val applicationFile: String,
    val overrideFiles: Map<String, String>,
    val releaseTo: String?,
    val flags: AuroraDeploymentConfigFlags,
    val resources: AuroraDeploymentConfigResources,
    val replicas: Int,
    val groupId: String,
    val artifactId: String,
    val version: String,
    val splunkIndex: String? = null,
    val database: List<Database> = listOf(),
    val certificateCn: String? = null,
    val webseal: Webseal? = null,
    val prometheus: HttpEndpoint? = null,
    val managementPath: String? = null,
    val serviceAccount: String? = null,
    val liveness: Probe?,
    val readiness: Probe?,
    val dockerImagePath: String,
    val dockerTag: String,
    val deployStrategy: AuroraDeployStrategy,
    val env: Map<String, String>
) {
    val dockerImage: String
        get() = "${dockerImagePath}:${dockerTag}"
}

data class AuroraDeployStrategy(
    val type: String, val timeout: Int
)

data class AuroraLocalTemplate(
    val parameters: Map<String, String>?,
    val templateJson: JsonNode
)

data class AuroraTemplate(
    val parameters: Map<String, String>?,
    val template: String
)

enum class MountType {
    ConfigMap, Secret, PVC
}

data class Mount(
    val path: String,
    val type: MountType,
    val mountName: String,
    val volumeName: String,
    val exist: Boolean,
    val content: Map<String, Any?>? = null,
    val secretVaultName: String? = null
)

data class Database(
    val name: String,
    val id: String? = null
) {
    val spec: String
        get():String = (id?.let { "$name:$id" } ?: name).toLowerCase()
    val envName: String
        get(): String = "${name}_db".toUpperCase()

    val dbName: String
        get(): String = "$name-db".toLowerCase()
}

data class Probe(val path: String? = null, val port: Int, val delay: Int, val timeout: Int)

data class Route(
    val name: String,
    val host: String? = null,
    val path: String? = null,
    val annotations: Map<String, String>? = null
)

data class AuroraDeploymentConfigFlags(
    val debug: Boolean = false,
    val alarm: Boolean = false,
    val pause: Boolean = false
)

data class AuroraDeploymentConfigResource(
    val cpu: String,
    val memory: String
)

data class AuroraDeploymentConfigResources(
    val limit: AuroraDeploymentConfigResource,
    val request: AuroraDeploymentConfigResource
)

data class HttpEndpoint(
    val path: String,
    val port: Int?
)

data class Webseal(
    val host: String?,
    val roles: String?
)

data class Permissions(
    val admin: Permission,
    val view: Permission? = null
)

data class Permission(
    val groups: Set<String>?,
    val users: Set<String>? = emptySet()
) {
    //In use in velocity template
    val rolebindings: Map<String, String>
        get(): Map<String, String> {
            val userPart = users?.map { Pair(it, "User") }?.toMap() ?: mapOf()
            val groupPart = groups?.map { Pair(it, "Group") }?.toMap() ?: mapOf()
            return userPart + groupPart
        }
}

