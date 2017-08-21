package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.databind.JsonNode

import no.skatteetaten.aurora.boober.mapper.AuroraConfigField

enum class TemplateType {
    deploy, development, localTemplate, template
}

interface AuroraDeploymentConfig {
    val schemaVersion: String
    val affiliation: String
    val cluster: String
    val type: TemplateType
    val name: String
    val envName: String
    val permissions: Permissions
    val secrets: Map<String, String>?
    val config: Map<String, String>
    val fields: Map<String, AuroraConfigField>
    val unmappedPointers: Map<String, List<String>>
    val route: List<Route>
    val mounts: List<Mount>?
    val releaseTo: String?
    val applicationFile: String
    val overrideFiles: Map<String, JsonNode>
    val namespace: String
        get() = if (envName.isBlank()) affiliation else "$affiliation-$envName"

    //In use in velocity template
    val routeName: String?
        get() =
            if (route.isEmpty()) {
                null
            } else {
                route.first().let {
                    val host = it.host ?: "$name-$namespace"
                    "http://$host.$cluster.paas.skead.no${it.path ?: ""}"
                }
            }

}


enum class MountType {
    ConfigMap, Secret
}

data class Mount(
        val path: String,
        val type: MountType,
        val mountName: String,
        val volumeName: String,
        val exist: Boolean,
        val content: Map<String, String>?
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

data class AuroraDeploymentConfigDeploy(
        override val schemaVersion: String = "v1",
        override val affiliation: String,
        override val cluster: String,
        override val type: TemplateType,
        override val name: String,
        val flags: AuroraDeploymentConfigFlags,
        val resources: AuroraDeploymentConfigResources,
        override val envName: String,
        override val permissions: Permissions,
        val replicas: Int?,
        override val secrets: Map<String, String>? = null,
        override val config: Map<String, String> = mapOf(),
        override val route: Route? = null,
        override val route: List<Route> = emptyList(),
        val groupId: String,
        val artifactId: String,
        val version: String,
        val extraTags: String,
        val splunkIndex: String? = null,
        val database: List<Database> = listOf(),
        val certificateCn: String? = null,
        val webseal: Webseal? = null,
        val prometheus: HttpEndpoint? = null,
        val managementPath: String? = null,
        val serviceAccount: String? = null,
        override val mounts: List<Mount>? = null,
        override val fields: Map<String, AuroraConfigField>,
        override val releaseTo: String? = null,
        override val unmappedPointers: Map<String, List<String>>,
        override val applicationFile: String,
        override val overrideFiles: Map<String, JsonNode> = emptyMap()
) : AuroraDeploymentConfig {

    //In use in velocity template
    val dockerGroup: String = groupId.replace(".", "_")
    //In use in velocity template
    val dockerName: String = artifactId
}


interface AuroraDeploymentConfigProcess {
    val parameters: Map<String, String>?
}


data class AuroraDeploymentConfigProcessLocalTemplate(
        override val schemaVersion: String = "v1",
        override val affiliation: String,
        override val cluster: String,
        override val type: TemplateType,
        override val name: String,
        override val envName: String,
        override val permissions: Permissions,
        override val secrets: Map<String, String>? = null,
        override val config: Map<String, String> = mapOf(),
        override val parameters: Map<String, String>? = mapOf(),
        override val fields: Map<String, AuroraConfigField>,
        override val route: List<Route> = emptyList(),
        override val mounts: List<Mount>? = null,
        override val releaseTo: String? = null,
        val templateJson: JsonNode,
        override val unmappedPointers: Map<String, List<String>>,
        override val applicationFile: String,
        override val overrideFiles: Map<String, JsonNode> = emptyMap()
) : AuroraDeploymentConfigProcess, AuroraDeploymentConfig

data class AuroraDeploymentConfigProcessTemplate(
        override val schemaVersion: String = "v1",
        override val affiliation: String,
        override val cluster: String,
        override val type: TemplateType,
        override val name: String,
        override val envName: String,
        override val permissions: Permissions,
        override val secrets: Map<String, String>? = null,
        override val config: Map<String, String> = mapOf(),
        override val parameters: Map<String, String>? = mapOf(),
        override val fields: Map<String, AuroraConfigField>,
        override val route: List<Route> = emptyList(),
        override val mounts: List<Mount>? = null,
        override val releaseTo: String? = null,
        val template: String,
        override val unmappedPointers: Map<String, List<String>>,
        override val applicationFile: String,
        override val overrideFiles: Map<String, JsonNode> = emptyMap()

) : AuroraDeploymentConfigProcess, AuroraDeploymentConfig


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
        val rolling: Boolean = false
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
        val host: String,
        val roles: String?
)


data class Permissions(
        val admin: Permission,
        val view: Permission? = null
)

data class Permission(
        val groups: Set<String>?,
        val users: Set<String>?
) {
    //In use in velocity template
    val rolebindings: Map<String, String>
        get(): Map<String, String> {
            val userPart = users?.map { Pair(it, "User") }?.toMap() ?: mapOf()
            val groupPart = groups?.map { Pair(it, "Group") }?.toMap() ?: mapOf()
            return userPart + groupPart
        }
}
