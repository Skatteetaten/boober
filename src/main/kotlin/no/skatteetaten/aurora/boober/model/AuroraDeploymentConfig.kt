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
    val config: Map<String, Map<String, String>>?
    val fields: Map<String, AuroraConfigField>
    val route: Route?
    val namespace: String
        get() = if (envName.isBlank()) affiliation else "$affiliation-$envName"

    //In use in velocity template
    val routeName: String?
        get() = route?.let {
            val host = it.host ?: "$name-$namespace"
            "http://$host.$cluster.paas.skead.no${it.path ?: ""}"
        }
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
        override val config: Map<String, Map<String, String>>? = null,
        override val route: Route? = null,
        val groupId: String,
        val artifactId: String,
        val version: String,
        val extraTags: String,
        val splunkIndex: String? = null,
        val database: String? = null,
        val certificateCn: String? = null,
        val webseal: Webseal? = null,
        val prometheus: HttpEndpoint? = null,
        val managementPath: String? = null,
        val serviceAccount: String? = null,
        override val fields: Map<String, AuroraConfigField>
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
        override val config: Map<String, Map<String, String>>? = null,
        override val parameters: Map<String, String>? = mapOf(),
        override val fields: Map<String, AuroraConfigField>,
        override val route: Route? = null,
        val templateJson: JsonNode
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
        override val config: Map<String, Map<String, String>>? = null,
        override val parameters: Map<String, String>? = mapOf(),
        override val fields: Map<String, AuroraConfigField>,
        override val route: Route? = null,

        val template: String

) : AuroraDeploymentConfigProcess, AuroraDeploymentConfig


data class Route(
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
