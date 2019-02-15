package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.mapper.platform.ApplicationPlatformHandler
import no.skatteetaten.aurora.boober.mapper.v1.DatabaseFlavor
import no.skatteetaten.aurora.boober.mapper.v1.DatabasePermission
import no.skatteetaten.aurora.boober.utils.addIfNotNull

import no.skatteetaten.aurora.boober.utils.ensureStartWith
import no.skatteetaten.aurora.boober.utils.openshiftName
import no.skatteetaten.aurora.boober.utils.withNonBlank
import java.time.Duration

enum class TemplateType {
    deploy, development, localTemplate, template
}

data class AuroraDeployHeader(
    val env: AuroraDeployEnvironment,
    val type: TemplateType,
    val applicationPlatform: ApplicationPlatformHandler,
    val name: String,
    val cluster: String,
    val segment: String?
) {
    fun extractPlaceHolders(): Map<String, String> {
        val segmentPair = segment?.let {
            "segment" to it
        }
        val placeholders = mapOf(
            "name" to name,
            "env" to env.envName,
            "affiliation" to env.affiliation,
            "cluster" to cluster
        ).addIfNotNull(segmentPair)
        return placeholders
    }
}

data class AuroraDeployEnvironment(
    val affiliation: String,
    val envName: String,
    val permissions: Permissions,
    val ttl: Duration?
) {
    val namespace: String
        get() = when {
            envName.isBlank() -> affiliation
            envName.startsWith("-") -> "${affiliation}$envName"
            else -> "$affiliation-$envName"
        }
}

data class AuroraDeploymentSpecInternal(
    val applicationDeploymentRef: ApplicationDeploymentRef,
    val schemaVersion: String,
    val type: TemplateType,
    val name: String,
    val spec: AuroraDeploymentSpec,
    val applicationPlatform: String = "java",
    val cluster: String,
    val environment: AuroraDeployEnvironment,
    val volume: AuroraVolume? = null,
    val route: AuroraRoute? = null,
    val build: AuroraBuild? = null,
    val deploy: AuroraDeploy? = null,
    val template: AuroraTemplate? = null,
    val localTemplate: AuroraLocalTemplate? = null,
    val integration: AuroraIntegration?,
    val applicationFile: AuroraConfigFile,
    val configVersion: String,
    val overrideFiles: Map<String, String>,
    val message: String?,
    val env: Map<String, String>
) {

    val appDeploymentId: String get() = "${environment.namespace}/$name"
    val appId: String
        get() =
            template?.let {
                it.template
            } ?: localTemplate?.let {
                "local" + it.templateJson.openshiftName
            } ?: deploy?.let {
                "${it.groupId}/${it.artifactId}"
            } ?: throw RuntimeException("Not valid deployment")

    val appName: String
        get() =
            template?.let {
                it.template
            } ?: localTemplate?.let {
                "local" + it.templateJson.openshiftName
            } ?: deploy?.let {
                it.artifactId
            } ?: throw RuntimeException("Not valid deployment")

    val version: String?
        get() = deploy?.let { deploy ->
            deploy.releaseTo?.withNonBlank { it } ?: deploy.version
        } ?: template?.version
        ?: localTemplate?.version
}

data class AuroraVolume(
    val secretVaultName: String?,
    val secretVaultKeys: List<String>,
    val keyMappings: Map<String, String>?,
    val config: Map<String, String>?,
    val mounts: List<Mount>?
)

data class AuroraRoute(
    val route: List<Route>
)

data class AuroraBuild(
    val baseName: String,
    val baseVersion: String,
    val builderName: String,
    val builderVersion: String,
    val groupId: String,
    val artifactId: String,
    val version: String,
    val outputKind: String,
    val outputName: String,
    val applicationPlatform: String
)

data class AuroraIntegration(
    val database: List<Database> = listOf(),
    val certificate: String? = null,
    val splunkIndex: String? = null,
    val webseal: Webseal? = null
)

data class AuroraDeploy(
    val applicationFile: String,
    val releaseTo: String?,
    val flags: AuroraDeploymentConfigFlags,
    val resources: AuroraDeploymentConfigResources,
    val replicas: Int,
    val groupId: String,
    val artifactId: String,
    val version: String,
    val prometheus: HttpEndpoint? = null,
    val managementPath: String? = null,
    val serviceAccount: String? = null,
    val liveness: Probe?,
    val readiness: Probe?,
    val dockerImagePath: String,
    val dockerTag: String,
    val deployStrategy: AuroraDeployStrategy,
    val ttl: Duration?,
    val toxiProxy: ToxiProxy?,
    val pause: Boolean = false
)

data class AuroraDeployStrategy(
    val type: String,
    val timeout: Int
)

data class AuroraLocalTemplate(
    val parameters: Map<String, String>?,
    val templateJson: JsonNode,
    val version: String? = null,
    val replicas: Int? = null
)

data class AuroraTemplate(
    val parameters: Map<String, String>?,
    val template: String,
    val version: String? = null,
    val replicas: Int? = null

)

enum class MountType(val kind: String) {
    ConfigMap("configmap"),
    Secret("secret"),
    PVC("persistentvolumeclaim")
}

data class Mount(
    val path: String,
    val type: MountType,
    val mountName: String,
    val volumeName: String,
    val exist: Boolean,
    val content: Map<String, String>? = null,
    val secretVaultName: String? = null,
    val targetContainer: String? = null
) {
    fun getNamespacedVolumeName(appName: String): String {
        val name = if (exist) {
            this.volumeName
        } else {
            this.volumeName.ensureStartWith(appName, "-")
        }
        return name.replace("_", "-").toLowerCase()
    }

    fun normalizeMountName() = mountName.replace("_", "-").toLowerCase()
}

data class Database(
    val name: String,
    val id: String? = null,
    val flavor: DatabaseFlavor,
    val generate: Boolean,
    val exposeTo: Map<String, String> = emptyMap(),
    val roles: Map<String, DatabasePermission> = emptyMap(),
    val parameters: Map<String, String> = emptyMap()
) {
    val spec: String
        get(): String = (id?.let { "$name:$id" } ?: name).toLowerCase()
}

data class Probe(val path: String? = null, val port: Int, val delay: Int, val timeout: Int)

data class Route(
    val objectName: String,
    val host: String,
    val path: String? = null,
    val annotations: Map<String, String>? = null
) {
    val target: String
        get(): String = if (path != null) "$host$path" else host
}

data class AuroraDeploymentConfigFlags(
    val debug: Boolean = false,
    val alarm: Boolean = false,
    val pause: Boolean = false
)

data class AuroraDeploymentConfigResource(
    val cpu: String,
    val memory: String
)

data class AuroraDeploymentConfigResources(
    val limit: AuroraDeploymentConfigResource,
    val request: AuroraDeploymentConfigResource
)

data class HttpEndpoint(
    val path: String,
    val port: Int?
)

data class Webseal(
    val host: String?,
    val roles: String?
)

data class Permissions(
    val admin: Permission,
    val view: Permission? = null
)

data class Permission(
    val groups: Set<String>?,
    val users: Set<String> = emptySet()
)

data class ToxiProxy(
    val version: String
)
