package no.skatteetaten.aurora.boober

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.treeToValue
import no.skatteetaten.aurora.boober.TemplateType.*
import org.springframework.stereotype.Service
import java.io.File


@Service
class ConfigService(val mapper: ObjectMapper) {

    fun createBooberResult(parentDir: File, jsonFiles: List<String>, overrides: Map<String, JsonNode>): Result {

        val jsonMap: Map<String, JsonNode> = jsonFiles.map{ Pair(it, mapper.readTree(File(parentDir, it)))}.toMap()

        val allJsonValues: List<JsonNode> = jsonMap.values.toList().plus(overrides.values)

        val mergedJson = allJsonValues.merge()

        val config: Config = mapper.treeToValue(mergedJson)
        return Result(config, jsonMap, overrides, parentDir)
    }
}

fun List<JsonNode>.merge() = this.reduce(::reduceJsonNodes)


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

data class Config(
        val affiliation: String,
        val groups: String?,
        val users: String?,
        val cluster: String,
        val type: TemplateType?,
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

    val cert:String? = if(flags != null && flags.contains("cert")) {
        build.groupId + "." + name
    } else {
        deploy?.certificate
    }
}

data class NamespaceResult(val results: Map<String, Result>)

data class Result(val config: Config, val sources: Map<String, JsonNode>, val overrides: Map<String, JsonNode>?, val parentDir: File) {

    //TODO: dette må vi legge i service laget ellern noe slikt
    fun validate(): Boolean {


        //namespace i booberConfig må overholde 1  ^[a-z0-9][-a-z0-9]*[a-z0-9]$ ]] || error_exit "Env name $envName is not correct must match the regex [a-z0-9]([-a-z0-9]*[a-z0-9])? (e.g. 'my-name' or '123-abc')"

        //affiliation  ^[a-z]{0,23}[a-z]$ ]] || error_exit "Affiliation can only contain lowercase letters (at most 24 characters)"

        when (config.type) {
            process -> {
                //if templateFile check that it exists in parentDir templates folder

                //if template must exist in openshift namespace

                //deploy cannot be set
            }
            deploy -> {
                //name  ^[a-z][-a-z0-9]{0,23}[a-z0-9]$ ]] DNS 952 label (at most 24 characters, matching regex [a-z]([-a-z0-9]*[a-z0-9])?): e.g. 'my-name'"

            }
            development -> {
                //name  ^[a-z][-a-z0-9]{0,23}[a-z0-9]$ ]] DNS 952 label (at most 24 characters, matching regex [a-z]([-a-z0-9]*[a-z0-9])?): e.g. 'my-name'"

            }
        }

        return true
    }
}
