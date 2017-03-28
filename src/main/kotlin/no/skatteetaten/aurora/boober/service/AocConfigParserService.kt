package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import no.skatteetaten.aurora.boober.model.*

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
        val flags = json.l("flags")
        var name: String? = json.s("name")

        val deploymentDescriptor: Any = when (type) {
            TemplateType.process -> {
                TemplateDeploy()
            }
            else -> {
                val buildJson: JsonNode = json.get("build")
                val configBuild = ConfigBuild(
                        buildJson.s("ARTIFACT_ID")!!,
                        buildJson.s("GROUP_ID")!!,
                        buildJson.s("VERSION")!!)
                name = name ?: configBuild.artifactId

                val deployJson: JsonNode = json.get("deploy")
                val configDeploy = ConfigDeploy(
                        deployJson.s("SPLUNK_INDEX") ?: "",
                        deployJson.s("MAX_MEMORY") ?: "256Mi",
                        deployJson.s("DATABASE") ?: "",
                        deployJson.s("CERTIFICATE_CN") ?: "",
                        deployJson.s("TAG") ?: "default",
                        deployJson.s("CPU_REQUEST") ?: "0",
                        deployJson.s("ROUTE_WEBSEAL") ?: "",
                        deployJson.s("ROUTE_WEBSEAL_ROLES") ?: "",
                        deployJson.b("PROMETHEUS_ENABLED") ?: false,
                        deployJson.i("PROMETHEUS_PORT") ?: 8080,
                        deployJson.s("PROMETHEUS_PATH") ?: "/prometheus",
                        deployJson.s("MANAGEMENT_PATH") ?: "",
                        deployJson.b("DEBUG") ?: false,
                        deployJson.b("ALARM") ?: true
                )

                val cert: String = if (flags.contains("cert")) {
                    configBuild.groupId + "." + name
                } else {
                    configDeploy.certificate
                }

                AuroraDeploy(configBuild, configDeploy, cert)
            }
        }

        val auroraDeploymentConfig = AuroraDeploymentConfig(
                affiliation = json.s("affiliation") ?: "",
                cluster = json.s("cluster") ?: "",
                config = json.m("config"),
                envName = json.s("envName") ?: "",
                flags = flags,
                groups = json.s("groups") ?: "",
                name = name!!,
                replicas = json.i("replicas") ?: 1,
                secretFile = json.s("secretFile") ?: "",
                type = type,
                users = json.s("users") ?: "",
                deployDescriptor = deploymentDescriptor
        )

        return auroraDeploymentConfig
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