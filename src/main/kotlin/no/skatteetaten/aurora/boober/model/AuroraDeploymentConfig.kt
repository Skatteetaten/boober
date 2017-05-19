package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.service.mapper.AuroraConfigField

data class AuroraDeploymentConfig(
        //TODO: Service account for våre objekter
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
        override val fields: Map<String, AuroraConfigField>
) : AuroraObjectsConfig {

    val dockerGroup: String = groupId.replace(".", "_")

    val dockerName: String = artifactId
}

interface AuroraObjectsConfig {
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

    val namespace: String
        get() = if (envName.isBlank()) affiliation else "$affiliation-$envName"

    val routeName: String?
        get() = "http://$name-$namespace.$cluster.paas.skead.no"

}

enum class TemplateType {
    deploy, development, process,
}

data class AuroraProcessConfig(
        override val schemaVersion: String = "v1",
        override val affiliation: String,
        override val cluster: String,
        override val type: TemplateType,
        override val name: String,
        override val envName: String,
        override val permissions: Permissions,
        override val secrets: Map<String, String>? = null,
        override val config: Map<String, Map<String, String>>? = null,
        val templateFile: String? = null,
        val templateJson: JsonNode? = null,
        val template: String? = null,
        val parameters: Map<String, String>? = mapOf(),
        val flags: AuroraProcessConfigFlags,
        override val fields: Map<String, AuroraConfigField>
) : AuroraObjectsConfig

data class AuroraDeploymentConfigFlags(
        val route: Boolean,
        val cert: Boolean,
        val debug: Boolean,
        val alarm: Boolean,
        val rolling: Boolean
)

data class AuroraProcessConfigFlags(
        val route: Boolean
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
        val groups: Set<String>?,
        val users: Set<String>?
) {
    val rolebindings: Map<String, String>
        get(): Map<String, String> {
            val userPart = users?.map { Pair(it, "User") }?.toMap() ?: mapOf()
            val groupPart = groups?.map { Pair(it, "Group") }?.toMap() ?: mapOf()
            return userPart + groupPart
        }
}
