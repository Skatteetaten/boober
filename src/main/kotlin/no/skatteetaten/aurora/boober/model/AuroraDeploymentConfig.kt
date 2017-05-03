package no.skatteetaten.aurora.boober.model

enum class TemplateType {
    deploy, development, process,
}

enum class DeploymentStrategy {
    rolling, recreate
}


data class AuroraProcessConfig(
        val schemaVersion: String = "v1",
        val affiliation: String,
        val cluster: String,
        val type: TemplateType,
        val name: String,
        val envName: String,
        val permissions: Map<String, Permission> = mapOf(),
        val secrets: Map<String, Map<String, String>> = mapOf(),
        val config: Map<String, Map<String, String>> = mapOf(),
        val templateFile: String? = null,
        val template: String? = null,
        val parameters: Map<String, String>? = mapOf()
)


data class AuroraDeploymentConfigFlags(
        val route: Boolean = false,
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
        val path: String,
        val roles: String?
)


data class Permissions(
        val admin: Permission
)

data class Permission(
        val groups: Set<String>,
        val users: Set<String>
) {
    val rolebindings: Map<String, String>
        get(): Map<String, String> {
            val userPart = users.map { Pair(it, "User") }.toMap()
            val groupPart = groups.map { Pair(it, "Group") }.toMap()
            val map = userPart.toMutableMap()
            map.putAll(groupPart)
            return map
        }
}

data class AuroraDeploymentConfig(
        val schemaVersion: String = "v1",
        val affiliation: String,
        val cluster: String,
        val type: TemplateType,
        val name: String,
        val flags: AuroraDeploymentConfigFlags,
        val resources: AuroraDeploymentConfigResources,
        val envName: String,
        val permissions: Permissions,
        val replicas: Int?,
        val secrets: Map<String, Map<String, String>>?,
        val config: Map<String, Map<String, String>>?,
        val artifactId: String,
        val groupId: String,
        val version: String,
        val extraTags: String = "latest,major,minor,patch",
        val splunkIndex: String?,
        val database: String?,
        val certificateCn: String? = "",
        val webseal: Webseal?,
        val prometheus: HttpEndpoint?,
        val managementPath: String?
) {
    /**
     * All the following properties should probably be derived where the OpenShift templates are evaluated.
     */
    val namespace: String
        get() = if (envName.isBlank()) affiliation else "$affiliation-$envName"

    val routeName: String?
        get() = "http://$name-$namespace.$cluster.paas.skead.no"

    val dockerGroup: String = groupId.replace(".", "_")

    val dockerName: String = artifactId
}
