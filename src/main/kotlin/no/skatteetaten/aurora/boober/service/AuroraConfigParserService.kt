package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.model.AuroraDeploy.Prometheus
import no.skatteetaten.aurora.boober.model.DeploymentStrategy.recreate
import no.skatteetaten.aurora.boober.model.DeploymentStrategy.rolling
import org.springframework.stereotype.Service

@Service
class AuroraConfigParserService {

    fun createAuroraDcFromAuroraConfig(auroraConfig: AuroraConfig, environmentName: String, applicationName: String): AuroraDeploymentConfig {

        val mergedJson = auroraConfig.getMergedFileForApplication(environmentName, applicationName)

        return createAuroraDeploymentConfig(mergedJson)
    }


    private fun createAuroraDeploymentConfig(json: Map<String, Any?>): AuroraDeploymentConfig {

        val type = json.s("type").let { TemplateType.valueOf(it!!) }
        var name = json.s("name")

        val deployDescriptor: DeployDescriptor = when (type) {
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

        val flags = json.a("flags")
        val auroraDeploymentConfig = AuroraDeploymentConfig(
                affiliation = json.s("affiliation")!!,
                cluster = json.s("cluster")!!,
                type = type,
                name = name!!,
                config = json.m("config"),
                envName = json.s("envName") ?: "",
                groups = json.s("groups") ?: "",
                replicas = json.i("replicas") ?: 1,
                secretFile = json.s("secretFile") ?: "",
                users = json.s("users") ?: "",
                route = flags?.contains("route") ?: false,
                deploymentStrategy = if (flags?.contains("rolling") ?: false) rolling else recreate,
                deployDescriptor = deployDescriptor
        )

        return auroraDeploymentConfig
    }

    private fun createAuroraDeploy(json: Map<String, Any?>): AuroraDeploy {

        val buildJson = json.m("build") ?: mapOf()
        val deployJson = json.m("deploy") ?: mapOf()

        val artifactId = buildJson.s("ARTIFACT_ID")
        val groupId = buildJson.s("GROUP_ID")

        val name: String? = json.s("name") ?: artifactId

        var certificateCn = deployJson.s("CERTIFICATE_CN")
        val generateCertificate = json.a("flags")?.contains("cert") ?: false || certificateCn != null
        if (certificateCn == null) {
            certificateCn = groupId + "." + name
        }

        return AuroraDeploy(
                artifactId = artifactId!!,
                groupId = groupId!!,
                version = buildJson.s("VERSION")!!,
                splunkIndex = deployJson.s("SPLUNK_INDEX") ?: "",
                maxMemory = deployJson.s("MAX_MEMORY") ?: "256Mi",
                database = deployJson.s("DATABASE"),
                generateCertificate = generateCertificate,
                certificateCn = certificateCn,
                tag = deployJson.s("TAG") ?: "default",
                cpuRequest = deployJson.s("CPU_REQUEST") ?: "0",
                websealRoute = deployJson.s("ROUTE_WEBSEAL"),
                websealRoles = deployJson.s("ROUTE_WEBSEAL_ROLES"),
                prometheus = createPrometheus(deployJson),
                managementPath = deployJson.s("MANAGEMENT_PATH") ?: "",
                debug = deployJson.b("DEBUG") ?: false,
                alarm = deployJson.b("ALARM") ?: true
        )
    }

    private fun createPrometheus(deployJson: Map<String, Any?>): Prometheus? {
        return if (deployJson.b("PROMETHEUS_ENABLED") ?: true) Prometheus(
                deployJson.s("PROMETHEUS_PORT")?.toInt() ?: 8080,
                deployJson.s("PROMETHEUS_PATH") ?: "/prometheus"
        ) else null
    }
}

fun Map<String, Any?>.s(field: String) = this[field]?.toString()
fun Map<String, Any?>.i(field: String) = this[field] as Int?
fun Map<String, Any?>.m(field: String) = this[field] as Map<String, Any?>?
fun Map<String, Any?>.b(field: String) = this[field] as Boolean?
fun Map<String, Any?>.a(field: String) = this[field] as List<String>?
