package no.skatteetaten.aurora.boober

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.treeToValue
import org.springframework.stereotype.Service
import java.io.File


@Service
class BooberConfigService(val mapper: ObjectMapper) {

    fun createBooberResult(parentDir:File, jsonFiles: List<String>): BooberResult {
        val jsonMap: Map<String, JsonNode> = jsonFiles.associateBy({ it }, { mapper.readTree(File(parentDir, it)) })
        val mergedJson: JsonNode = jsonMap.values.reduce(::reduceJsonNodes)
        val booberConfig: BooberConfig = mapper.treeToValue(mergedJson)
        return BooberResult(booberConfig, jsonMap)
    }
}


enum class TemplateType {
    deploy, development, process,
}

data class BooberConfigBuild(
        @JsonProperty("ARTIFACT_ID") val artifactId: String,
        @JsonProperty("GROUP_ID") val groupId: String,
        @JsonProperty("VERSION") val version: String
)

data class BooberConfigDeploy(
        @JsonProperty("SPLUNK_INDEX") val splunkIndex: String?,
        @JsonProperty("MAX_MEMORY") val maxMemory: String?,
        @JsonProperty("DATABASE") val database: String?,
        @JsonProperty("CERTIFICATE_CN") val certificate: String?,
        @JsonProperty("TAG") val tag: String = "default",
        @JsonProperty("CPU_REQUEST") val cpuRequest: Int = 0,
        @JsonProperty("ROUTE_WEBSEAL") val websealRoute: String?,
        @JsonProperty("ROUTE_WEBSEAL_ROLES") val websealRoles: String?,
        @JsonProperty("ROUTE_WEB2WEB") val web2web: String?,
        @JsonProperty("ROUTE_WEB2APP") val web2app: String?,
        @JsonProperty("PROMETHEUS_ENABLED") val prometheus: Boolean = true,
        @JsonProperty("PROMETHEUS_PORT") val prometheusPort: Int = 8081,
        @JsonProperty("PROMETHEUS_PATH") val prometheusPath: String = "/prometheus",
        @JsonProperty("MANAGEMENT_PATH") val managementPath: String = ":8081/actuator",
        @JsonProperty("DEBUG") val debug: Boolean = false
)

data class BooberConfig(
        val affiliation: String,
        val groups: String?,
        val users: String?,
        val cluster: String,
        val name: String?,
        val type: TemplateType?,
        val replicas: Int = 1,
        val flags: List<String>?,
        val build: BooberConfigBuild,
        val deploy: BooberConfigDeploy?,
        val config: Map<String, String>?,
        val secretFile: String?,
        val templateFile: String?,
        val template:String?,
        val parameters: Map<String, String>?,
        val envName: String?
) {
    fun name() = name ?: build.artifactId

    fun dockerGroup() = build.groupId.replace(".", "_")
}

data class BooberResult(val config: BooberConfig, val sources: Map<String, JsonNode>)
