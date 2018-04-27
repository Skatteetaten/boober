package no.skatteetaten.aurora.boober.mapper.platform

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.configMap
import com.fkorotkov.kubernetes.persistentVolumeClaim
import com.fkorotkov.kubernetes.secret
import com.fkorotkov.kubernetes.volume
import io.fabric8.kubernetes.api.model.Volume
import io.fabric8.kubernetes.api.model.VolumeMount
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraDeploy
import no.skatteetaten.aurora.boober.model.AuroraDeployStrategy
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfigResource
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.Database
import no.skatteetaten.aurora.boober.model.Mount
import no.skatteetaten.aurora.boober.model.MountType.ConfigMap
import no.skatteetaten.aurora.boober.model.MountType.PVC
import no.skatteetaten.aurora.boober.model.MountType.Secret
import no.skatteetaten.aurora.boober.model.Probe
import no.skatteetaten.aurora.boober.service.OpenShiftObjectLabelService
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.ensureStartWith
import no.skatteetaten.aurora.boober.utils.filterNullValues
import java.time.Duration

abstract class ApplicationPlatformHandler(val name: String) {
    open fun handlers(handlers: Set<AuroraConfigFieldHandler>): Set<AuroraConfigFieldHandler> = handlers

    abstract fun handleAuroraDeployment(auroraDeploymentSpec: AuroraDeploymentSpec, labels: Map<String, String>, mounts: List<Mount>?): AuroraDeployment

    fun createEnvVars(mounts: List<Mount>?, auroraDeploymentSpec: AuroraDeploymentSpec): Map<String, String> {

        val mountEnv = mounts?.map {
            "VOLUME_${it.mountName.toUpperCase().replace("-", "_")}" to it.path
        }?.toMap() ?: mapOf()

        val splunkIndex = auroraDeploymentSpec.deploy?.splunkIndex?.let { "SPLUNK_INDEX" to it }

        val certEnv = auroraDeploymentSpec.deploy?.certificateCn?.let {
            val baseUrl = "/u01/secrets/app/${auroraDeploymentSpec.name}-cert"
            mapOf(
                    "STS_CERTIFICATE_URL" to "$baseUrl/certificate.crt",
                    "STS_PRIVATE_KEY_URL" to "$baseUrl/privatekey.key",
                    "STS_KEYSTORE_DESCRIPTOR" to "$baseUrl/descriptor.properties"
            )
        } ?: mapOf()

        val debugEnv = auroraDeploymentSpec.deploy?.flags?.takeIf { it.debug }?.let {
            mapOf(
                    "ENABLE_REMOTE_DEBUG" to "true",
                    "DEBUG_PORT" to "5005"
            )
        } ?: mapOf()

        val configEnv = auroraDeploymentSpec.deploy?.env ?: emptyMap()

        val routeName = auroraDeploymentSpec.route?.route?.takeIf { it.isNotEmpty() }?.first()?.let {
            val host = auroraDeploymentSpec.assembleRouteHost(it.host ?: auroraDeploymentSpec.name)

            val url = "$host${it.path?.ensureStartWith("/") ?: ""}"
            mapOf("ROUTE_NAME" to url, "ROUTE_URL" to "http://$url")
        } ?: mapOf()

        val dbEnv = auroraDeploymentSpec.deploy?.database?.takeIf { it.isNotEmpty() }?.let {
            fun createDbEnv(db: Database, envName: String): List<Pair<String, String>> {
                val path = "/u01/secrets/app/${db.name.toLowerCase()}-db"
                val envName = envName.replace("-", "_").toUpperCase()

                return listOf(
                        envName to "$path/info",
                        "${envName}_PROPERTIES" to "$path/db.properties"
                )
            }

            it.flatMap { createDbEnv(it, "${it.name}_db") } + createDbEnv(it.first(), "db")
        }?.toMap() ?: mapOf()

        val envs = mapOf(
                "OPENSHIFT_CLUSTER" to auroraDeploymentSpec.cluster,
                "APP_NAME" to auroraDeploymentSpec.name
        ).addIfNotNull(splunkIndex) + routeName + certEnv + debugEnv + dbEnv + mountEnv + configEnv

        return envs.mapKeys { it.key.replace(".", "_").replace("-", "_") }

    }

    fun createAnnotations(deploy: AuroraDeploy): Map<String, String> {


        fun escapeOverrides(): String? {
            val files = deploy.overrideFiles.mapValues { jacksonObjectMapper().readValue(it.value, JsonNode::class.java) }
            val content = jacksonObjectMapper().writeValueAsString(files)
            return content.takeIf { it != "{}" }
        }

        return mapOf(
                "boober.skatteetaten.no/applicationFile" to deploy.applicationFile,
                "console.skatteetaten.no/alarm" to deploy.flags.alarm.toString(),
                "boober.skatteetaten.no/overrides" to escapeOverrides(),
                "console.skatteetaten.no/management-path" to deploy.managementPath,
                "boober.skatteetaten.no/releaseTo" to deploy.releaseTo,
                "sprocket.sits.no/deployment-config.certificate" to deploy.certificateCn
        ).filterNullValues().filterValues { !it.isBlank() }
    }

    fun createLabels(name: String, deploy: AuroraDeploy, labels: Map<String, String>): Map<String, String> {


        val deployTag = "deployTag" to (deploy.releaseTo?.withNonBlank { it } ?: deploy.version)
        val pauseLabel = if (deploy.flags.pause) {
            "paused" to "true"
        } else null

        val allLabels = labels + mapOf(
                "name" to name,
                deployTag
        ).addIfNotNull(pauseLabel)
        return OpenShiftObjectLabelService.toOpenShiftLabelNameSafeMap(allLabels)
    }

    private inline fun <R> String.withNonBlank(block: (String) -> R?): R? {

        if (this.isBlank()) {
            return null
        }
        return block(this)
    }
}


data class AuroraContainer(val name: String,
                           val tcpPorts: Map<String, Int>,
                           val args: List<String>? = null,
                           val readiness: Probe?,
                           val liveness: Probe?,
                           val limit: AuroraDeploymentConfigResource,
                           val request: AuroraDeploymentConfigResource,
                           val env: Map<String, String>,
                           val mounts: List<Mount>? = null,
                           val shouldHaveImageChange: Boolean = true)

data class AuroraDeployment(val name: String,
                            val tag: String,
                            val containers: List<AuroraContainer>,
                            val labels: Map<String, String>,
                            val mounts: List<Mount>? = null,
                            val annotations: Map<String, String>,
                            val deployStrategy: AuroraDeployStrategy,
                            val replicas: Int,
                            val serviceAccount: String?,
                            val ttl: Duration?)


enum class DeploymentState {
    Stateless, Stateful, Daemon
}

fun List<Mount>?.volumeMount(): List<VolumeMount>? {
    return this?.map {
        com.fkorotkov.kubernetes.volumeMount {
            name = it.normalizeMountName()
            mountPath = it.path
        }
    }
}

fun List<Mount>?.podVolumes(dcName: String): List<Volume> {
    return this?.map {
        val volumeName = it.getNamespacedVolumeName(dcName)
        volume {
            name = it.normalizeMountName()
            when (it.type) {
                ConfigMap -> configMap {
                    name = volumeName
                    items = null
                }
                Secret -> secret {
                    secretName = volumeName
                    items = null
                }
                PVC -> persistentVolumeClaim {
                    claimName = volumeName
                }
            }
        }
    } ?: emptyList()
}
