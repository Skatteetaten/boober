package no.skatteetaten.aurora.boober.model

import javax.validation.constraints.NotNull
import javax.validation.constraints.Pattern
import javax.validation.constraints.Size

enum class TemplateType {
    deploy, development, process,
}

enum class DeploymentStrategy {
    rolling, recreate
}

class AuroraDeploymentConfig(

        @get:NotNull
        @get:Pattern(message = "Only lowercase letters, max 24 length", regexp = "^[a-z]{0,23}[a-z]$")
        val affiliation: String?,

        @get:NotNull
        val cluster: String?,

        @get:NotNull
        val type: TemplateType?,

        @get:Pattern(message = "Must be valid DNSDNS952 label", regexp = "^[a-z][-a-z0-9]{0,23}[a-z0-9]$")
        val name: String?,
        val envName: String?,
        val groups: String?,
        val users: String?,
        val replicas: Int?,
        val secretFile: String?,
        val config: Map<String, Any?>?,
        val route: Boolean = false,
        val deploymentStrategy: DeploymentStrategy?,
        val deployDescriptor: Any?
) {
    @get:Pattern(message = "Alphanumeric and dashes. Cannot end or start with dash", regexp = "^[a-z0-9][-a-z0-9]*[a-z0-9]$")
    val namespace: String
        get() = "$affiliation$envName"

    val routeName: String?
        get() = "http://$name-$namespace.$cluster.paas.skead.no"

    val schemaVersion: String?
        get() = "v1"
}

data class TemplateDeploy(
        val templateFile: String? = null,
        val template: String? = null,
        val parameters: Map<String, String>? = mapOf()
)

data class AuroraDeploy(

        @get:NotNull
        @get:Size(min = 1, max = 50)
        val artifactId: String?,

        @get:NotNull
        @get:Size(min = 1, max = 200)
        val groupId: String?,

        @get:NotNull
        @get:Size(min = 1)
        val version: String?,

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
) {
    val dockerGroup: String? = groupId?.replace(".", "_")
    val dockerName: String? = artifactId

    data class Prometheus(
            val port: Int?,
            val path: String?
    )
}
