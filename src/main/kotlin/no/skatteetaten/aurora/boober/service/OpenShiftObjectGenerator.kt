package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.model.*
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
import java.util.*

@Service
class OpenShiftObjectGenerator(
        val userDetailsProvider: UserDetailsProvider,
        val ve: VelocityEngine,
        val mapper: ObjectMapper,
        val openShiftTemplateProcessor: OpenShiftTemplateProcessor,
        val openShiftClient: OpenShiftResourceClient) {

    val logger: Logger = LoggerFactory.getLogger(OpenShiftObjectGenerator::class.java)

    fun generateBuildRequest(name: String): JsonNode {
        logger.debug("Generating build request for name $name")
        return mergeVelocityTemplate("buildrequest.json", mapOf("name" to name))

    }

    fun generateDeploymentRequest(name: String): JsonNode {
        logger.debug("Generating deploy request for name $name")
        return mergeVelocityTemplate("deploymentrequest.json", mapOf("name" to name))

    }

    fun generateObjects(auroraResource: AuroraResource, deployId: String): List<JsonNode> {


        val mounts: List<Mount>? = findMounts(auroraResource)

        val labels = findLabels(auroraResource, deployId, auroraResource.name)

        return generateProject(auroraResource)
                .addIfNotNull(generateRolebindings(auroraResource.permissions))
                .addIfNotNull(generateDeploymentConfig(auroraResource, labels, mounts))
                .addIfNotNull(generateService(auroraResource, labels))
                .addIfNotNull(generateImageStream(auroraResource, labels))
                .addIfNotNull(generateBuilds(auroraResource, deployId))
                .addIfNotNull(generateMount(mounts, labels))
                .addIfNotNull(generateRoute(auroraResource, labels))
                .addIfNotNull(generateTemplate(auroraResource))
                .addIfNotNull(generateLocalTemplate(auroraResource))

    }

    fun generateProject(auroraResource: AuroraResource): LinkedList<JsonNode> {
        val templatesToProcess = listOf(
                "project.json",
                "deployer-rolebinding.json",
                "imagebuilder-rolebinding.json",
                "imagepuller-rolebinding.json"
        )

        return LinkedList(templatesToProcess.map {
            mergeVelocityTemplate(it, mapOf(
                    "namespace" to auroraResource.namespace,
                    "affiliation" to auroraResource.affiliation,
                    "username" to userDetailsProvider.getAuthenticatedUser().username))
        })
    }

    fun generateRolebindings(permissions: Permissions): List<JsonNode>? {
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


    fun generateDeploymentConfig(auroraResource: AuroraResource,
                                 labels: Map<String, String>,
                                 mounts: List<Mount>?): JsonNode? {

        return auroraResource.deploy?.let {
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
            val env = findEnv(mounts, auroraResource)

            val deployTag = release?.let {
                it
            } ?: it.version
            val tag = if (auroraResource.type == development) {
                "latest"
            } else {
                "default"
            }
            val params = mapOf(
                    "annotations" to annotations
                            .addIfNotNull(releaseToAnnotation)
                            .addIfNotNull(overrides)
                            .addIfNotNull(managementPath)
                            .addIfNotNull(cert)
                            .addIfNotNull(database),
                    "labels" to labels + mapOf("name" to auroraResource.name, "deployTag" to deployTag),
                    "name" to auroraResource.name,
                    "deploy" to it,
                    "mounts" to mounts,
                    "env" to env,
                    "imageStreamTag" to tag
            )

            mergeVelocityTemplate("deployment-config.json", params)
        }
    }

    fun generateService(auroraResource: AuroraResource, labels: Map<String, String>): JsonNode? {
        return auroraResource.deploy?.let {

            val webseal = it.webseal?.let {
                "sprocket.sits.no/service.webseal" to it.host
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
                    "name" to auroraResource.name,
                    "annotations" to prometheusAnnotations.addIfNotNull(webseal).addIfNotNull(websealRoles)
            ))
        }

    }

    fun generateImageStream(auroraResource: AuroraResource, labels: Map<String, String>): JsonNode? {
        return auroraResource.deploy?.let {
            mergeVelocityTemplate("imagestream.json", mapOf(
                    "labels" to labels + mapOf("releasedVersion" to it.version),
                    "deploy" to it,
                    "name" to auroraResource.name,
                    "type" to auroraResource.type.name
            ))
        }
    }

    fun generateLocalTemplate(auroraResource: AuroraResource): List<JsonNode>? {
        return auroraResource.localTemplate?.let {
            openShiftTemplateProcessor.generateObjects(it.templateJson as ObjectNode,
                    it.parameters, auroraResource)
        }
    }

    fun generateTemplate(auroraResource: AuroraResource): List<JsonNode>? {
        return auroraResource.template?.let {
            val template = openShiftClient.get("template", it.template, "openshift")?.body as ObjectNode
            openShiftTemplateProcessor.generateObjects(template,
                    it.parameters, auroraResource)
        }
    }

    fun generateRoute(auroraResource: AuroraResource, labels: Map<String, String>): List<JsonNode>? {
        return auroraResource.route?.route?.map {
            logger.debug("Route is {}", it)
            val routeParams = mapOf(
                    "name" to auroraResource.name,
                    "route" to it,
                    "labels" to labels)
            mergeVelocityTemplate("route.json", routeParams)
        }
    }

    fun generateMount(mounts: List<Mount>?, labels: Map<String, String>): List<JsonNode>? {
        return mounts?.filter { !it.exist }?.map {
            logger.debug("Create manual mount {}", it)
            val mountParams = mapOf(
                    "mount" to it,
                    "labels" to labels
            )
            mergeVelocityTemplate("mount.json", mountParams)
        }
    }

    private fun generateBuilds(auroraResource: AuroraResource, deployId: String): List<JsonNode>? {
        return auroraResource.build?.let {
            val buildName = if (it.buildSuffix != null) {
                "${auroraResource.name}-${it.buildSuffix}"
            } else {
                auroraResource.name
            }

            val buildParams = mapOf(
                    "labels" to findLabels(auroraResource, deployId, buildName),
                    "buildName" to buildName,
                    "build" to it
            )
            val bc = mergeVelocityTemplate("build-config.json", buildParams)

            val testBc = if (it.testGitUrl != null) {
                mergeVelocityTemplate("jenkins-build-config.json", buildParams)
            } else {
                null
            }

            listOf(bc).addIfNotNull(testBc)
        }
    }

    fun findLabels(auroraResource: AuroraResource, deployId: String, name: String): Map<String, String> {
        val labels = mapOf(
                "app" to name,
                "updatedBy" to userDetailsProvider.getAuthenticatedUser().username,
                "affiliation" to auroraResource.affiliation,
                "booberDeployId" to deployId
        )
        return labels
    }

    fun findEnv(mounts: List<Mount>?, auroraResource: AuroraResource): Map<String, String> {
        val mountEnv = mounts?.map {
            "VOLUME_${it.mountName.toUpperCase().replace("-", "_")}" to it.path
        }?.toMap() ?: mapOf()

        val splunkIndex = auroraResource.deploy?.splunkIndex?.let { "SPLUNK_INDEX" to it }

        val certEnv = auroraResource.deploy?.certificateCn?.let {
            val baseUrl = "/u01/secrets/app/${auroraResource.name}-cert"
            mapOf(
                    "STS_CERTIFICATE_URL" to "$baseUrl/certificate.crt",
                    "STS_PRIVATE_KEY_URL" to "$baseUrl/privatekey.key",
                    "STS_KEYSTORE_DESCRIPTOR" to "$baseUrl/descriptor.properties"
            )
        } ?: mapOf()

        val debugEnv = auroraResource.deploy?.flags?.takeIf { it.debug }?.let {
            mapOf(
                    "REMOTE_DEBUG" to "true",
                    "DEBUG_PORT" to "5005"
            )
        } ?: mapOf()

        val routeName = auroraResource.route?.route?.takeIf { it.isNotEmpty() }?.first()?.let {
            val host = it.host ?: "${auroraResource.name}-${auroraResource.namespace}"
            "ROUTE_NAME" to "http://$host.${auroraResource.cluster}.paas.skead.no${it.path ?: ""}"
        }

        val dbEnv = auroraResource.deploy?.database?.takeIf { it.isNotEmpty() }?.let {
            fun createDbEnv(db: Database, envName: String): List<Pair<String, String>> {
                val path = "/u01/secrets/app/${db.name.toLowerCase()}-db"
                val envName = envName.toUpperCase()

                return listOf(
                        envName to "$path/info",
                        "${envName}_PROPERTIES" to "$path/db.properties"
                )
            }

            it.flatMap { createDbEnv(it, "${it.name}_db") } + createDbEnv(it.first(), "db")
        }?.toMap() ?: mapOf()

        return mapOf(
                "OPENSHIFT_CLUSTER" to auroraResource.cluster,
                "HTTP_PORT" to "8080",
                "MANAGEMENT_HTTP_PORT" to "8081",
                "APP_NAME" to auroraResource.name
        ).addIfNotNull(splunkIndex).addIfNotNull(routeName) + certEnv + debugEnv + dbEnv + mountEnv
    }

    fun findMounts(auroraResource: AuroraResource): List<Mount> {
        val mounts: List<Mount> = auroraResource.volume?.mounts?.map {
            if (it.exist) {
                it
            } else {
                it.copy(volumeName = it.volumeName.ensureStartWith(auroraResource.name, "-"))
            }
        } ?: emptyList()


        val configMount = auroraResource.volume?.config?.let {

            Mount(path = "/u01/config/configmap",
                    type = MountType.ConfigMap,
                    volumeName = auroraResource.name,
                    mountName = "config",
                    exist = false,
                    content = it)
        }

        val secretMount = auroraResource.volume?.secrets?.let {
            Mount(path = "/u01/config/secret",
                    type = MountType.Secret,
                    volumeName = auroraResource.name,
                    mountName = "secrets",
                    exist = false,
                    content = it)
        }

        val certMount = auroraResource.deploy?.certificateCn?.let {
            Mount(path = "/u01/secrets/app/${auroraResource.name}-cert",
                    type = MountType.Secret,
                    volumeName = "${auroraResource.name}-cert",
                    mountName = "${auroraResource.name}-cert",
                    exist = true,
                    content = null)
            //TODO: Add sprocket content here
        }

        val databaseMounts = auroraResource.deploy?.database?.map {
            val dbName = "${it.name}-db".toLowerCase()
            Mount(path = "/u01/secrets/app/$dbName",
                    type = MountType.Secret,
                    volumeName = dbName,
                    mountName = dbName,
                    exist = true,
                    content = null)
            //TODO add sprocket content here
        } ?: emptyList()
        return databaseMounts + mounts.addIfNotNull(configMount).addIfNotNull(secretMount).addIfNotNull(certMount)
    }


    private fun mergeVelocityTemplate(template: String, content: Map<String, Any?>): JsonNode {
        val context = VelocityContext()
        content.forEach { context.put(it.key, it.value) }
        val t = ve.getTemplate("templates/$template.vm")
        val sw = StringWriter()
        t.merge(context, sw)
        return mapper.readTree(sw.toString())
    }
}