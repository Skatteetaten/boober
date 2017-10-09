package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.databind.JsonNode

import no.skatteetaten.aurora.boober.mapper.AuroraConfigField


enum class TemplateType {
    deploy, development, localTemplate, template, build
}

enum class ApplicationPlatform {
    java, web
}


data class AuroraApplication(
        val schemaVersion: String,
        val affiliation: String,
        val cluster: String,
        val type: TemplateType,
        val name: String,
        val envName: String,
        val permissions: Permissions,
        val fields: Map<String, AuroraConfigField>,

        val volume: AuroraVolume? = null,
        val route: AuroraRoute? = null,
        val build: AuroraBuild? = null,
        val deploy: AuroraDeploy? = null,
        val template: AuroraTemplate? = null,
        val localTemplate: AuroraLocalTemplate? = null
) {
    val namespace: String
        get() = if (envName.isBlank()) affiliation else "$affiliation-$envName"

}

data class AuroraVolume(
        val secrets: Map<String, String>?,
        val config: Map<String, String>?,
        val mounts: List<Mount>?,
        val permissions: AuroraPermissions?
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
        val testJenkinsfile: String?,
        val extraTags: String,
        val groupId: String,
        val artifactId: String,
        val version: String,
        val outputKind: String,
        val outputName: String,
        val triggers: Boolean,
        val buildSuffix: String?,
        val applicationPlatform: ApplicationPlatform
)


data class AuroraDeploy(
        val applicationFile: String,
        val overrideFiles: Map<String, JsonNode>,
        val releaseTo: String?,
        val flags: AuroraDeploymentConfigFlags,
        val resources: AuroraDeploymentConfigResources,
        val replicas: Int?,
        val applicationPlatform: ApplicationPlatform = ApplicationPlatform.java,
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
        val deployStrategy: AuroraDeployStrategy
)

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
    ConfigMap, Secret
}

data class Mount(
        val path: String,
        val type: MountType,
        val mountName: String,
        val volumeName: String,
        val exist: Boolean,
        val content: Map<String, String>?,
        val permissions: AuroraPermissions? = null
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
        val cert: Boolean = false,
        val debug: Boolean = false,
        val alarm: Boolean = false,
        val pause: Boolean = false
)

data class AuroraDeploymentConfigResource(
        val min: String,
        val max: String
)

data class AuroraDeploymentConfigResources(
        val memory: AuroraDeploymentConfigResource,
        val cpu: AuroraDeploymentConfigResource
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
        val groups: Set<String>?
) {
    //In use in velocity template
    val rolebindings: Map<String, String>
        get(): Map<String, String> {
            return groups?.map { Pair(it, "Group") }?.toMap() ?: mapOf()
        }
}
