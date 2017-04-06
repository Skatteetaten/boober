package no.skatteetaten.aurora.boober.model

enum class TemplateType {
    deploy, development, process,
}

enum class DeploymentStrategy {
    rolling, recreate
}

data class AuroraDeploymentConfig(
        val affiliation: String,
        val cluster: String,
        val type: TemplateType,
        val name: String,
        val envName: String,
        val groups: Set<String>?,
        val users: Set<String>?,
        val replicas: Int?,
        val secrets: Map<String, Any?>?,
        val config: Map<String, Any?>?,
        val route: Boolean = false,
        val deploymentStrategy: DeploymentStrategy?,
        val deployDescriptor: DeployDescriptor?
) {
    val namespace: String
        get() = "$affiliation-$envName"

    val routeName: String?
        get() = "http://$name-$namespace.$cluster.paas.skead.no"

    val schemaVersion: String?
        get() = "v1"
    val rolebindings: Map<String, String>
        get(): Map<String, String> {
            val userPart = users?.map { Pair(it, "User") }?.toMap() ?: emptyMap()
            val groupPart = groups?.map { Pair(it, "Group") }?.toMap() ?: emptyMap()
            val map = userPart.toMutableMap()
            map.putAll(groupPart)
            return map
        }
}

interface DeployDescriptor{}

data class TemplateDeploy (
        val templateFile: String? = null,
        val template: String? = null,
        val parameters: Map<String, String>? = mapOf()
) : DeployDescriptor

data class AuroraDeploy(
        val artifactId: String,
        val groupId: String,
        val version: String,
        val extraTags: String? = "latest,major,minor,patch",
        val splunkIndex: String?,
        val maxMemory: String?,
        val database: String?,
        val generateCertificate: Boolean = false,
        val certificateCn: String? = "",
        val tag: String?,
        val cpuRequest: String?,
        val websealRoute: String?,
        val websealRoles: String?,
        val prometheus: Prometheus?,
        val managementPath: String?,
        val debug: Boolean = false,
        val alarm: Boolean = true
) : DeployDescriptor {
    val dockerGroup: String = groupId.replace(".", "_")
    val dockerName: String = artifactId

    data class Prometheus(
            val port: Int?,
            val path: String?
    )
}
