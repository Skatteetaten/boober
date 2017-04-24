package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.model.AuroraDeploy.Prometheus
import no.skatteetaten.aurora.boober.model.DeploymentStrategy.recreate
import no.skatteetaten.aurora.boober.model.DeploymentStrategy.rolling
import no.skatteetaten.aurora.boober.utils.Result
import no.skatteetaten.aurora.boober.utils.orElseThrow
import org.springframework.stereotype.Service

@Service
class AuroraConfigService(
        val gitService: GitService,
        val openShiftClient: OpenShiftClient) {

    fun save(affiliation: String, auroraConfig: AuroraConfig) {

        validate(auroraConfig)
        gitService.saveFilesAndClose(affiliation, auroraConfig.auroraConfigFiles)
    }

    fun findAuroraConfigForAffiliationForUpdate(affiliation: String, function: (AuroraConfig) -> Unit): AuroraConfig {

        val repo = gitService.checkoutRepoForAffiliation(affiliation)
        val filesForAffiliation = gitService.getAllFilesInRepo(repo)
        val auroraConfig = AuroraConfig(auroraConfigFiles = filesForAffiliation)

        function(auroraConfig)

        validate(auroraConfig)

        gitService.saveFilesAndClose(repo, auroraConfig.auroraConfigFiles)

        return auroraConfig
    }

    fun createAuroraDcsForApplications(auroraConfig: AuroraConfig, applicationIds: List<ApplicationId>, validateOpenShiftReferences: Boolean = true): List<AuroraDeploymentConfig> {

        return applicationIds.map { aid ->
            val result: Result<AuroraDeploymentConfig?, Error?> = try {
                Result(value = createAuroraDcForApplication(auroraConfig, aid, validateOpenShiftReferences))
            } catch (e: ApplicationConfigException) {
                Result(error = Error(aid, e.errors))
            }
            result
        }.orElseThrow {
            AuroraConfigException("AuroraConfig contained errors for one or more applications", it)
        }
    }

    fun createAuroraDcForApplication(auroraConfig: AuroraConfig, aid: ApplicationId, validateOpenShiftReferences: Boolean = true): AuroraDeploymentConfig {

        val mergedFile: Map<String, Any?> = auroraConfig.getMergedFileForApplication(aid)
        val secrets: Map<String, String>? = mergedFile.s("secretFolder")?.let { auroraConfig.getSecrets(it) }

        val type = mergedFile.s("type").let { TemplateType.valueOf(it!!) }
        var name = mergedFile.s("name")

        val deployDescriptor: DeployDescriptor = when (type) {
            TemplateType.process -> {
                TemplateDeploy()
            }
            else -> {
                val auroraDeploy = createAuroraDeploy(mergedFile)
                // This is kind of messy. Name should probably be required.
                name = name ?: auroraDeploy.artifactId
                auroraDeploy
            }
        }

        val flags = mergedFile.a("flags")
        val auroraDeploymentConfig = AuroraDeploymentConfig(
                affiliation = mergedFile.s("affiliation")!!,
                cluster = mergedFile.s("cluster")!!,
                type = type,
                name = name!!,
                config = mergedFile.m("config"),
                secrets = secrets,
                envName = mergedFile.s("envName") ?: "",
                groups = mergedFile.s("groups")?.split(" ")?.toSet() ?: emptySet(),
                replicas = mergedFile.i("replicas") ?: 1,
                users = mergedFile.s("users")?.split(" ")?.toSet() ?: emptySet(),
                route = flags?.contains("route") ?: false,
                deploymentStrategy = if (flags?.contains("rolling") ?: false) rolling else recreate,
                deployDescriptor = deployDescriptor
        )

        return auroraDeploymentConfig.apply { if(validateOpenShiftReferences) validateOpenShiftReferences(this) }
    }


    /**
     * Validates that references to objects on OpenShift in the configuration are valid.
     *
     * This method should probably be extracted into its own class at some point when we add more validation,
     * like references to templates, etc.
     */
    private fun validateOpenShiftReferences(auroraDc: AuroraDeploymentConfig) {
        val errors: MutableList<String> = mutableListOf()
        auroraDc.groups
                .filter { !openShiftClient.isValidGroup(it) }
                .takeIf { it.isNotEmpty() }
                ?.let { errors.add("The following groups are not valid=${it.joinToString()}") }

        auroraDc.users
                .filter { !openShiftClient.isValidUser(it) }
                .takeIf { it.isNotEmpty() }
                ?.let { errors.add("The following users are not valid=${it.joinToString()}") }

        if (errors.isNotEmpty()) {
            throw ApplicationConfigException("Configuration contained references to one or more objects on OpenShift that does not exist", errors = errors)
        }
    }

    private fun createAuroraDeploy(json: Map<String, Any?>): AuroraDeploy {

        fun createPrometheus(deployJson: Map<String, Any?>): Prometheus? {
            return if (deployJson.b("PROMETHEUS_ENABLED") ?: true) Prometheus(
                    deployJson.s("PROMETHEUS_PORT")?.toInt() ?: 8080,
                    deployJson.s("PROMETHEUS_PATH") ?: "/prometheus"
            ) else null
        }

        val buildJson = json.m("build") ?: mapOf()
        val deployJson = json.m("deploy") ?: mapOf()

        val artifactId = buildJson.s("ARTIFACT_ID")
        val groupId = buildJson.s("GROUP_ID")

        val name: String? = json.s("name") ?: artifactId


        val generatedCN = json.a("flags")?.contains("cert")?.let {
            groupId + "." + name
        }

        val certificateCn = deployJson.s("CERTIFICATE_CN") ?: generatedCN

        val tag = if (json.s("type") == "development") {
            "latest"
        } else {
            deployJson.s("TAG") ?: "default"
        }

        return AuroraDeploy(
                artifactId = artifactId!!,
                groupId = groupId!!,
                version = buildJson.s("VERSION")!!,
                splunkIndex = deployJson.s("SPLUNK_INDEX") ?: "",
                maxMemory = deployJson.s("MAX_MEMORY") ?: "256Mi",
                database = deployJson.s("DATABASE"),
                certificateCn = certificateCn,
                tag = tag,
                cpuRequest = deployJson.s("CPU_REQUEST") ?: "0",
                websealRoute = deployJson.s("ROUTE_WEBSEAL"),
                websealRoles = deployJson.s("ROUTE_WEBSEAL_ROLES"),
                prometheus = createPrometheus(deployJson),
                managementPath = deployJson.s("MANAGEMENT_PATH") ?: "",
                debug = deployJson.b("DEBUG") ?: false,
                alarm = deployJson.b("ALARM") ?: true
        )
    }

    private fun validate(auroraConfig: AuroraConfig) {
        val appIds = auroraConfig.getApplicationIds()
        // Verify that all AuroraDeploymentConfigs represented by the AuroraConfig are valid
        createAuroraDcsForApplications(auroraConfig, appIds)
    }
}

fun Map<String, Any?>.s(field: String) = this[field]?.toString()
fun Map<String, Any?>.i(field: String) = this[field] as Int?
fun Map<String, Any?>.m(field: String) = this[field] as Map<String, Any?>?
fun Map<String, Any?>.b(field: String) = this[field] as Boolean?
fun Map<String, Any?>.a(field: String) = this[field] as List<String>?
