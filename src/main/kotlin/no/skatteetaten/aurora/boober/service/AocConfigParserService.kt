package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.model.AuroraDeploy.Prometheus
import no.skatteetaten.aurora.boober.model.DeploymentStrategy.recreate
import no.skatteetaten.aurora.boober.model.DeploymentStrategy.rolling

class AocConfigParserService(
        val validationService: ValidationService
) {

    fun createConfigFromAocConfigFiles(aocConfig: AocConfig, environmentName: String, applicationName: String): AuroraDeploymentConfig {

        val mergedJson = aocConfig.getMergedFileForApplication(environmentName, applicationName)

        val schemaVersion = mergedJson.get("schemaVersion")?.asText() ?: "v1"

        if (schemaVersion != "v1") {
            TODO("Only schema v1 supported")
        }

        val auroraDc = createAuroraDeploymentConfig(mergedJson)

        validationService.assertIsValid(auroraDc)

        return auroraDc
    }

    private fun createAuroraDeploymentConfig(json: JsonNode): AuroraDeploymentConfig {

        val type = TemplateType.valueOf(json.s("type") ?: "")
        var name = json.s("name")

        val deployDescriptor: Any = when (type) {
            TemplateType.process -> {
                TemplateDeploy()
            }
            else -> {
                val auroraDeploy = createAuroraDeploy(json)
                // This is kind of messy. Name should probably be required.
                name = name ?: auroraDeploy.artifactId
                auroraDeploy
            }
        }

        val flags = json.l("flags")
        val auroraDeploymentConfig = AuroraDeploymentConfig(
                affiliation = json.s("affiliation") ?: "",
                cluster = json.s("cluster") ?: "",
                config = json.m("config"),
                envName = json.s("envName") ?: "",
                groups = json.s("groups") ?: "",
                name = name!!,
                replicas = json.i("replicas") ?: 1,
                secretFile = json.s("secretFile") ?: "",
                type = type,
                users = json.s("users") ?: "",
                route = flags.contains("route"),
                deploymentStrategy = if (flags.contains("rolling")) rolling else recreate,
                deployDescriptor = deployDescriptor
        )

        return auroraDeploymentConfig
    }

    private fun createAuroraDeploy(json: JsonNode): AuroraDeploy {

        val buildJson: JsonNode = json.get("build")
        val deployJson: JsonNode = json.get("deploy")

        val artifactId = buildJson.s("ARTIFACT_ID")
        val groupId = buildJson.s("GROUP_ID")

        var name: String? = json.s("name")
        name = name ?: artifactId

        var certificateCn = deployJson.s("CERTIFICATE_CN")
        val generateCertificate = json.l("flags").contains("cert") || certificateCn != null
        if (generateCertificate && certificateCn == null) {
            certificateCn = groupId + "." + name
        }

        val prometheus = if (deployJson.b("PROMETHEUS_ENABLED") ?: true) Prometheus(
                deployJson.i("PROMETHEUS_PORT") ?: 8080,
                deployJson.s("PROMETHEUS_PATH") ?: "/prometheus"
        ) else null
        return AuroraDeploy(
                artifactId = artifactId!!,
                groupId = groupId!!,
                version = buildJson.s("VERSION")!!,
                splunkIndex = deployJson.s("SPLUNK_INDEX") ?: "",
                maxMemory = deployJson.s("MAX_MEMORY") ?: "256Mi",
                database = deployJson.s("DATABASE"),
                generateCertificate = generateCertificate,
                certificateCn = certificateCn!!,
                tag = deployJson.s("TAG") ?: "default",
                cpuRequest = deployJson.s("CPU_REQUEST") ?: "0",
                websealRoute = deployJson.s("ROUTE_WEBSEAL"),
                websealRoles = deployJson.s("ROUTE_WEBSEAL_ROLES"),
                prometheus = prometheus,
                managementPath = deployJson.s("MANAGEMENT_PATH") ?: "",
                debug = deployJson.b("DEBUG") ?: false,
                alarm = deployJson.b("ALARM") ?: true
        )
    }
}

fun JsonNode.s(field: String): String? = this.get(field)?.asText()
fun JsonNode.i(field: String): Int? = this.get(field)?.asInt()
fun JsonNode.b(field: String): Boolean? = this.get(field)?.asBoolean()
fun JsonNode.l(field: String): List<String> = this.get(field)?.toList()?.map(JsonNode::asText) ?: listOf()
fun JsonNode.m(field: String): Map<String, String> {
    val objectNode = this.get(field) as ObjectNode?
    return mutableMapOf<String, String>().apply {
        objectNode?.fields()?.forEach { put(it.key, it.value.asText()) }
    }
}