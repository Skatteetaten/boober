package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.model.ApplicationPlatform.java
import no.skatteetaten.aurora.boober.model.ApplicationPlatform.web
import no.skatteetaten.aurora.boober.model.TemplateType.development
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.ensureStartWith
import org.apache.commons.lang.StringEscapeUtils
import org.apache.velocity.VelocityContext
import org.apache.velocity.app.VelocityEngine
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.StringWriter

@Service
class OpenShiftObjectGenerator(
        val userDetailsProvider: UserDetailsProvider,
        val ve: VelocityEngine,
        val mapper: ObjectMapper,
        val openShiftTemplateProcessor: OpenShiftTemplateProcessor,
        val openShiftClient: OpenShiftResourceClient) {

    val logger: Logger = LoggerFactory.getLogger(OpenShiftObjectGenerator::class.java)

    companion object {
        @JvmStatic
        val MAX_LABEL_VALUE_LENGTH = 63

        /**
         * Returns a new Map where each value has been truncated as to not exceed the
         * <code>MAX_LABEL_VALUE_LENGTH</code> max length.
         * Truncation is done by cutting of characters from the start of the value, leaving only the last
         * MAX_LABEL_VALUE_LENGTH characters.
         */
        fun toOpenShiftLabelNameSafeMap(labels: Map<String, String>): Map<String, String> {
            return labels.mapValues {
                val startIndex = (it.value.length - MAX_LABEL_VALUE_LENGTH).takeIf { it >= 0 } ?: 0
                it.value.substring(startIndex)
            }
        }
    }

    fun generateBuildRequest(name: String): JsonNode {
        logger.trace("Generating build request for name $name")
        return mergeVelocityTemplate("buildrequest.json", mapOf("name" to name))

    }

    fun generateDeploymentRequest(name: String): JsonNode {
        logger.trace("Generating deploy request for name $name")
        return mergeVelocityTemplate("deploymentrequest.json", mapOf("name" to name))

    }

    fun generateApplicationObjects(auroraDeploymentSpec: AuroraDeploymentSpec, deployId: String): List<JsonNode> {

        return withLabelsAndMounts(auroraDeploymentSpec, deployId, { labels, mounts ->
            listOf<JsonNode>()
                    .addIfNotNull(generateDeploymentConfig(auroraDeploymentSpec, labels, mounts))
                    .addIfNotNull(generateService(auroraDeploymentSpec, labels))
                    .addIfNotNull(generateImageStream(auroraDeploymentSpec, labels))
                    .addIfNotNull(generateBuilds(auroraDeploymentSpec, deployId))
                    .addIfNotNull(generateMount(mounts, labels))
                    .addIfNotNull(generateRoute(auroraDeploymentSpec, labels))
                    .addIfNotNull(generateTemplate(auroraDeploymentSpec))
                    .addIfNotNull(generateLocalTemplate(auroraDeploymentSpec))
        })
    }

    fun generateProjectRequest(auroraDeploymentSpec: AuroraDeploymentSpec): JsonNode {

        return mergeVelocityTemplate("projectrequest.json", mapOf(
                "namespace" to auroraDeploymentSpec.namespace
        ))
    }

    fun generateRolebindings(permissions: Permissions): List<JsonNode> {
        val admin = mergeVelocityTemplate("rolebinding.json", mapOf(
                "permission" to permissions.admin,
                "name" to "admin"
        ))

        val view = permissions.view?.let {
            mergeVelocityTemplate("rolebinding.json", mapOf(
                    "permission" to it,
                    "name" to "view"))
        }

        return listOf(admin).addIfNotNull(view)
    }

    fun generateDeploymentConfig(deploymentSpec: AuroraDeploymentSpec, deployId: String): JsonNode? {

        return withLabelsAndMounts(deploymentSpec, deployId, { labels, mounts -> generateDeploymentConfig(deploymentSpec, labels, mounts) })
    }

    fun generateDeploymentConfig(auroraDeploymentSpec: AuroraDeploymentSpec,
                                 labels: Map<String, String>,
                                 mounts: List<Mount>?): JsonNode? {

        return auroraDeploymentSpec.deploy?.let {
            val template = when (auroraDeploymentSpec.deploy.applicationPlatform) {
                java -> "deployment-config.json"
                web -> "deployment-config-web.json"
            }

            val annotations = mapOf(
                    "boober.skatteetaten.no/applicationFile" to it.applicationFile,
                    "console.skatteetaten.no/alarm" to it.flags.alarm.toString()
            )

            val cert = it.certificateCn?.takeIf { it.isNotBlank() }?.let {
                "sprocket.sits.no/deployment-config.certificate" to it
            }

            val database = it.database.takeIf { it.isNotEmpty() }?.map { it.spec }?.joinToString(",")?.let {
                "sprocket.sits.no/deployment-config.database" to it
            }

            val overrides = StringEscapeUtils.escapeJavaScript(mapper.writeValueAsString(it.overrideFiles)).takeIf { it != "{}" }?.let {
                "boober.skatteetaten.no/overrides" to it
            }

            val managementPath = it.managementPath?.takeIf { it.isNotBlank() }?.let {
                "console.skatteetaten.no/management-path" to it
            }

            val release = it.releaseTo?.takeIf { it.isNotBlank() }

            val releaseToAnnotation = release?.let {
                "boober.skatteetaten.no/releaseTo" to it
            }
            val env = findEnv(mounts, auroraDeploymentSpec)

            val deployTag = release?.let {
                it
            } ?: it.version
            val tag = if (auroraDeploymentSpec.type == development) {
                "latest"
            } else {
                "default"
            }


            val pauseLabel = if (auroraDeploymentSpec.deploy.flags.pause) {
                "paused" to "true"
            } else null

            val dcLabels = toOpenShiftLabelNameSafeMap(labels + mapOf("name" to auroraDeploymentSpec.name, "deployTag" to deployTag).addIfNotNull(pauseLabel))
            val params = mapOf(
                    "annotations" to annotations
                            .addIfNotNull(releaseToAnnotation)
                            .addIfNotNull(overrides)
                            .addIfNotNull(managementPath)
                            .addIfNotNull(cert)
                            .addIfNotNull(database),
                    "labels" to dcLabels,
                    "name" to auroraDeploymentSpec.name,
                    "deploy" to it,
                    "mounts" to mounts,
                    "env" to env,
                    "imageStreamTag" to tag
            )

            mergeVelocityTemplate(template, params)
        }
    }

    fun generateService(auroraDeploymentSpec: AuroraDeploymentSpec, labels: Map<String, String>): JsonNode? {
        return auroraDeploymentSpec.deploy?.let {

            val webseal = it.webseal?.let {
                val host = it.host ?: "${auroraDeploymentSpec.name}-${auroraDeploymentSpec.namespace}"
                "sprocket.sits.no/service.webseal" to host
            }

            val websealRoles = it.webseal?.roles?.let {
                "sprocket.sits.no/service.webseal-roles" to it
            }

            val prometheusAnnotations = it.prometheus?.takeIf { it.path != "" }?.let {
                mapOf("prometheus.io/scheme" to "http",
                        "prometheus.io/scrape" to "true",
                        "prometheus.io/path" to it.path,
                        "prometheus.io/port" to it.port
                )
            } ?: mapOf("prometheus.io/scrape" to "false")


            mergeVelocityTemplate("service.json", mapOf(
                    "labels" to labels,
                    "name" to auroraDeploymentSpec.name,
                    "annotations" to prometheusAnnotations.addIfNotNull(webseal).addIfNotNull(websealRoles)
            ))
        }

    }

    fun generateImageStream(deployId: String, auroraDeploymentSpec: AuroraDeploymentSpec) =
            withLabelsAndMounts(auroraDeploymentSpec, deployId) { labels, _ ->
                generateImageStream(auroraDeploymentSpec, labels)
            }

    fun generateImageStream(auroraDeploymentSpec: AuroraDeploymentSpec, labels: Map<String, String>): JsonNode? {
        return auroraDeploymentSpec.deploy?.let {
            mergeVelocityTemplate("imagestream.json", mapOf(
                    "labels" to toOpenShiftLabelNameSafeMap(labels + mapOf("releasedVersion" to it.version)),
                    "deploy" to it,
                    "name" to auroraDeploymentSpec.name,
                    "type" to auroraDeploymentSpec.type.name
            ))
        }
    }

    fun generateLocalTemplate(auroraDeploymentSpec: AuroraDeploymentSpec): List<JsonNode>? {
        return auroraDeploymentSpec.localTemplate?.let {
            openShiftTemplateProcessor.generateObjects(it.templateJson as ObjectNode, it.parameters, auroraDeploymentSpec)
        }
    }

    fun generateTemplate(auroraDeploymentSpec: AuroraDeploymentSpec): List<JsonNode>? {
        return auroraDeploymentSpec.template?.let {
            val template = openShiftClient.get("template", "openshift", it.template)?.body as ObjectNode
            openShiftTemplateProcessor.generateObjects(template, it.parameters, auroraDeploymentSpec)
        }
    }

    fun generateRoute(auroraDeploymentSpec: AuroraDeploymentSpec, labels: Map<String, String>): List<JsonNode>? {
        return auroraDeploymentSpec.route?.route?.map {
            logger.trace("Route is {}", it)
            val host = it.host?.let {
                auroraDeploymentSpec.assembleRouteHost(it)
            }
            val routeParams = mapOf(
                    "name" to auroraDeploymentSpec.name,
                    "route" to it,
                    "host" to host,
                    "labels" to labels)
            mergeVelocityTemplate("route.json", routeParams)
        }
    }

    fun generateMount(deploymentSpec: AuroraDeploymentSpec, deployId: String): List<JsonNode>? {

        return withLabelsAndMounts(deploymentSpec, deployId, { labels, mounts -> generateMount(mounts, labels) })
    }

    private fun generateMount(mounts: List<Mount>?, labels: Map<String, String>): List<JsonNode>? {
        return mounts?.filter { !it.exist }?.map {
            logger.trace("Create manual mount {}", it)
            val mountParams = mapOf(
                    "mount" to it,
                    "labels" to labels
            )
            mergeVelocityTemplate("mount.json", mountParams)
        }
    }

    private fun generateBuilds(auroraDeploymentSpec: AuroraDeploymentSpec, deployId: String): List<JsonNode>? {
        return auroraDeploymentSpec.build?.let {
            val buildName = if (it.buildSuffix != null) {
                "${auroraDeploymentSpec.name}-${it.buildSuffix}"
            } else {
                auroraDeploymentSpec.name
            }

            val buildParams = mapOf(
                    "labels" to findLabels(auroraDeploymentSpec, deployId, buildName),
                    "buildName" to buildName,
                    "build" to it
            )
            val applicationPlatform = it.applicationPlatform
            val template = when (applicationPlatform) {
                java -> "build-config.json"
                web -> "build-config-web.json"
            }
            val bc = mergeVelocityTemplate(template, buildParams)

            val testBc = if (it.testGitUrl != null) {
                mergeVelocityTemplate("jenkins-build-config.json", buildParams)
            } else {
                null
            }

            listOf(bc).addIfNotNull(testBc)
        }
    }

    fun findLabels(auroraDeploymentSpec: AuroraDeploymentSpec, deployId: String, name: String = auroraDeploymentSpec.name): Map<String, String> {
        val labels = mapOf(
                "app" to name,
                "updatedBy" to userDetailsProvider.getAuthenticatedUser().username.replace(":", "-"),
                "affiliation" to auroraDeploymentSpec.affiliation,
                "booberDeployId" to deployId
        )
        return labels
    }

    fun findEnv(mounts: List<Mount>?, auroraDeploymentSpec: AuroraDeploymentSpec): Map<String, String> {
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
                    "REMOTE_DEBUG" to "true",
                    "DEBUG_PORT" to "5005"
            )
        } ?: mapOf()

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

        return mapOf(
                "OPENSHIFT_CLUSTER" to auroraDeploymentSpec.cluster,
                "HTTP_PORT" to "8080",
                "MANAGEMENT_HTTP_PORT" to "8081",
                "APP_NAME" to auroraDeploymentSpec.name
        ).addIfNotNull(splunkIndex) + routeName + certEnv + debugEnv + dbEnv + mountEnv
    }

    fun findMounts(auroraDeploymentSpec: AuroraDeploymentSpec): List<Mount> {
        val mounts: List<Mount> = auroraDeploymentSpec.volume?.mounts?.map {
            if (it.exist) {
                it
            } else {
                it.copy(volumeName = it.volumeName.ensureStartWith(auroraDeploymentSpec.name, "-"))
            }
        } ?: emptyList()


        val configMount = auroraDeploymentSpec.volume?.config?.let {

            Mount(path = "/u01/config/configmap",
                    type = MountType.ConfigMap,
                    volumeName = auroraDeploymentSpec.name,
                    mountName = "config",
                    exist = false,
                    content = it, permissions = null)
        }

        val secretMount = auroraDeploymentSpec.volume?.secrets?.let {
            Mount(path = "/u01/config/secret",
                    type = MountType.Secret,
                    volumeName = auroraDeploymentSpec.name,
                    mountName = "secrets",
                    exist = false,
                    content = it)
        }

        val certMount = auroraDeploymentSpec.deploy?.certificateCn?.let {
            Mount(path = "/u01/secrets/app/${auroraDeploymentSpec.name}-cert",
                    type = MountType.Secret,
                    volumeName = "${auroraDeploymentSpec.name}-cert",
                    mountName = "${auroraDeploymentSpec.name}-cert",
                    exist = true,
                    content = null)
            //TODO: Add sprocket content here
        }

        val databaseMounts = auroraDeploymentSpec.deploy?.database?.map {
            val dbName = "${it.name}-db".toLowerCase()
            Mount(path = "/u01/secrets/app/$dbName",
                    type = MountType.Secret,
                    mountName = dbName,
                    volumeName = dbName,
                    exist = true,
                    content = null)
            //TODO add sprocket content here
        } ?: emptyList()
        return databaseMounts + mounts.addIfNotNull(configMount).addIfNotNull(secretMount).addIfNotNull(certMount)
    }

    private fun <T> withLabelsAndMounts(deploymentSpec: AuroraDeploymentSpec, deployId: String, c: (labels: Map<String, String>, mounts: List<Mount>?) -> T): T {

        val mounts = findMounts(deploymentSpec)
        val labels = toOpenShiftLabelNameSafeMap(findLabels(deploymentSpec, deployId, deploymentSpec.name))
        return c(labels, mounts)
    }

    private fun mergeVelocityTemplate(template: String, content: Map<String, Any?>): JsonNode {

        val context = VelocityContext().apply {
            content.forEach { put(it.key, it.value) }
        }
        val t = ve.getTemplate("templates/$template.vm")
        val sw = StringWriter()
        t.merge(context, sw)
        val mergedResult = sw.toString()

        return mapper.readTree(mergedResult)
    }


    fun generateImageStreamImport(name: String, dockerImage: String): JsonNode {

        return mergeVelocityTemplate("imagestreamimport.json", mapOf("name" to name, "docker" to dockerImage))
    }
}