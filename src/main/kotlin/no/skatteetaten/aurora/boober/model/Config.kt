package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import javax.validation.Valid
import javax.validation.constraints.NotNull
import javax.validation.constraints.Pattern
import javax.validation.constraints.Size

enum class TemplateType {
    deploy, development, process,
}

data class ConfigBuild(

        @JsonProperty("ARTIFACT_ID")
        @get:NotNull
        @get:Size(min = 1)
        val artifactId: String,

        @JsonProperty("GROUP_ID")
        @get:NotNull
        @get:Size(min = 1)
        val groupId: String,

        @JsonProperty("VERSION")
        @get:NotNull
        @get:Size(min = 1)
        val version: String
)


data class ConfigDeploy(
        @JsonProperty("SPLUNK_INDEX") val splunkIndex: String = "",
        @JsonProperty("MAX_MEMORY") val maxMemory: String = "256Mi",
        @JsonProperty("DATABASE") val database: String = "",
        @JsonProperty("CERTIFICATE_CN") val certificate: String = "",
        @JsonProperty("TAG") val tag: String = "default",
        @JsonProperty("CPU_REQUEST") val cpuRequest: String = "0",
        @JsonProperty("ROUTE_WEBSEAL") val websealRoute: String = "",
        @JsonProperty("ROUTE_WEBSEAL_ROLES") val websealRoles: String = "",
        @JsonProperty("PROMETHEUS_ENABLED") val prometheus: Boolean = false,
        @JsonProperty("PROMETHEUS_PORT") val prometheusPort: Int = 8080,
        @JsonProperty("PROMETHEUS_PATH") val prometheusPath: String = "/prometheus",
        @JsonProperty("MANAGEMENT_PATH") val managementPath: String = "",
        @JsonProperty("DEBUG") val debug: Boolean = false,
        @JsonProperty("ALARM") val alarm: Boolean = true
) {
    companion object {
        fun empty() = ConfigDeploy()
    }
}

interface Config {
    val cluster: String
    val envName: String


    @get:Pattern(message = "Only lowercase letters, max 24 length", regexp = "^[a-z]{0,23}[a-z]$")
    val affiliation: String

    @get:Pattern(message = "Must be valid DNSDNS952 label", regexp = "^[a-z][-a-z0-9]{0,23}[a-z0-9]$")
    val name: String

    val groups: String
    val users: String
    val type: TemplateType
    val replicas: Int
    val flags: List<String>
    val secretFile: String?
    val config: Map<String, String>

    @get:Pattern(message = "Alphanumeric and dashes. Cannot end or start with dash", regexp = "^[a-z0-9][-a-z0-9]*[a-z0-9]$")
    val namespace: String
        get() = "$affiliation$envName"

    val routeName: String
        get() = "http://$name-$namespace.$cluster.paas.skead.no"

    val configLine: String
        get() = config.map { "${it.key}=${it.value}" }.joinToString(separator = "\\n", transform = { it })

    val schemaVersion: String
        get() = "v1"

    val route: Boolean
        get() = flags.contains("route")

    val rolling: Boolean
        get() = flags.contains("rolling")
}

data class ProcessConfig(
        override val affiliation: String,
        override val groups: String = "",
        override val users: String = "",
        override val cluster: String,
        override val type: TemplateType = TemplateType.process,
        override val replicas: Int = 1,
        override val flags: List<String> = listOf(),
        override val name: String,
        override val config: Map<String, String> = mapOf(),
        override val secretFile: String? = null,
        override val envName: String,
        val templateFile: String? = null,
        val template: String? = null,
        val parameters: Map<String, String> = mapOf()
) : Config

data class AppConfig(
        override val affiliation: String,
        override val groups: String = "",
        override val users: String = "",
        override val cluster: String,
        override val type: TemplateType,
        override val replicas: Int = 1,
        override val flags: List<String> = listOf(),
        @get:Valid
        val build: ConfigBuild,
        override val name: String = build.artifactId,
        val deploy: ConfigDeploy = ConfigDeploy(),
        override val config: Map<String, String> = mapOf(),
        override val secretFile: String? = null,
        override val envName: String
) : Config {

    val dockerGroup: String = build.groupId.replace(".", "_")
    val dockerName: String = build.artifactId

    val cert: String = if (flags.contains("cert")) {
        build.groupId + "." + name
    } else {
        deploy.certificate
    }
}

data class NamespaceResult(val results: Map<String, Result>)

data class Result(val config: Config? = null,
                  val sources: Map<String, JsonNode> = mapOf(),
                  val errors: List<String> = listOf(),
                  val openshiftObjects: Map<String, JsonNode>? = mapOf()) {

    val valid = errors.isEmpty()

}
