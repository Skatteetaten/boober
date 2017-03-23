package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode

enum class TemplateType {
    deploy, development, process,
}

data class ConfigBuild(
        @JsonProperty("ARTIFACT_ID") val artifactId: String,
        @JsonProperty("GROUP_ID") val groupId: String,
        @JsonProperty("VERSION") val version: String
)

data class ConfigDeploy(
        @JsonProperty("SPLUNK_INDEX") val splunkIndex: String?,
        @JsonProperty("MAX_MEMORY") val maxMemory: String?,
        @JsonProperty("DATABASE") val database: String?,
        @JsonProperty("CERTIFICATE_CN") val certificate: String?,
        @JsonProperty("TAG") val tag: String = "default",
        @JsonProperty("CPU_REQUEST") val cpuRequest: String = "",
        @JsonProperty("ROUTE_WEBSEAL") val websealRoute: String?,
        @JsonProperty("ROUTE_WEBSEAL_ROLES") val websealRoles: String?,
        @JsonProperty("PROMETHEUS_ENABLED") val prometheus: Boolean = true,
        @JsonProperty("PROMETHEUS_PORT") val prometheusPort: Int = 8081,
        @JsonProperty("PROMETHEUS_PATH") val prometheusPath: String = "/prometheus",
        @JsonProperty("MANAGEMENT_PATH") val managementPath: String = ":8081/actuator",
        @JsonProperty("DEBUG") val debug: Boolean = false,
        @JsonProperty("ALARM") val alarm: Boolean = true
)

data class Config(
        val schemaVersion:String="v1",
        val affiliation: String,
        val groups: String?,
        val users: String?,
        val cluster: String,
        val type: TemplateType,
        val replicas: Int = 1,
        val flags: List<String>?,
        val build: ConfigBuild,
        val name: String = build.artifactId,
        val deploy: ConfigDeploy?,
        val config: Map<String, String>?,
        val secretFile: String?,
        val templateFile: String?,
        val template: String?,
        val parameters: Map<String, String>?,
        val envName: String?
) {

    val dockerGroup: String = build.groupId.replace(".", "_")
    val dockerName: String = build.artifactId
    val namespace: String = if (envName != null) "$affiliation$envName" else "$affiliation-$name"

    val cert: String? = if (flags != null && flags.contains("cert")) {
        build.groupId + "." + name
    } else {
        deploy?.certificate
    }
}

data class NamespaceResult(val results: Map<String, Result>)

data class Result(val config: Config? = null,
                  val sources: Map<String, JsonNode>,
                  val errors: List<String> = listOf(),
                  val openshiftObjects: Map<String, JsonNode>? = mapOf()) {

    val valid = errors.isEmpty()

}
