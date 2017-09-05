package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.model.ApplicationPlatform.java
import no.skatteetaten.aurora.boober.model.ApplicationPlatform.web
import no.skatteetaten.aurora.boober.model.AuroraApplication
import no.skatteetaten.aurora.boober.model.Database
import no.skatteetaten.aurora.boober.model.Mount
import no.skatteetaten.aurora.boober.model.MountType
import no.skatteetaten.aurora.boober.model.Permissions
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
import java.io.File
import java.io.StringWriter

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

    fun generateObjects(auroraApplication: AuroraApplication, deployId: String): List<JsonNode> {

        val mounts: List<Mount>? = findMounts(auroraApplication)

        val labels = findLabels(auroraApplication, deployId)

        return listOf(generateProject(auroraApplication))
                .addIfNotNull(generateDeploymentConfig(auroraApplication, labels, mounts))
                .addIfNotNull(generateService(auroraApplication, labels))
                .addIfNotNull(generateImageStream(auroraApplication, labels))
                .addIfNotNull(generateBuilds(auroraApplication, deployId))
                .addIfNotNull(generateMount(mounts, labels))
                .addIfNotNull(generateRoute(auroraApplication, labels))
                .addIfNotNull(generateTemplate(auroraApplication))
                .addIfNotNull(generateLocalTemplate(auroraApplication))
                .addIfNotNull(generateRolebindings(auroraApplication.permissions))
    }

    fun generateProject(auroraApplication: AuroraApplication): JsonNode {

        return mergeVelocityTemplate("project.json", mapOf(
                "namespace" to auroraApplication.namespace
        ))
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


    fun generateDeploymentConfig(auroraApplication: AuroraApplication,
                                 labels: Map<String, String>,
                                 mounts: List<Mount>?): JsonNode? {

        return auroraApplication.deploy?.let {
            val template = when (auroraApplication.deploy.applicationPlatform) {
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
            val env = findEnv(mounts, auroraApplication)

            val deployTag = release?.let {
                it
            } ?: it.version
            val tag = if (auroraApplication.type == development) {
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
                    "labels" to labels + mapOf("name" to auroraApplication.name, "deployTag" to deployTag),
                    "name" to auroraApplication.name,
                    "deploy" to it,
                    "mounts" to mounts,
                    "env" to env,
                    "imageStreamTag" to tag
            )

            mergeVelocityTemplate(template, params)
        }
    }

    fun generateService(auroraApplication: AuroraApplication, labels: Map<String, String>): JsonNode? {
        return auroraApplication.deploy?.let {

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
                    "name" to auroraApplication.name,
                    "annotations" to prometheusAnnotations.addIfNotNull(webseal).addIfNotNull(websealRoles)
            ))
        }

    }

    fun generateImageStream(auroraApplication: AuroraApplication, labels: Map<String, String>): JsonNode? {
        return auroraApplication.deploy?.let {
            mergeVelocityTemplate("imagestream.json", mapOf(
                    "labels" to labels + mapOf("releasedVersion" to it.version),
                    "deploy" to it,
                    "name" to auroraApplication.name,
                    "type" to auroraApplication.type.name
            ))
        }
    }

    fun generateLocalTemplate(auroraApplication: AuroraApplication): List<JsonNode>? {
        return auroraApplication.localTemplate?.let {
            openShiftTemplateProcessor.generateObjects(it.templateJson as ObjectNode,
                    it.parameters, auroraApplication)
        }
    }

    fun generateTemplate(auroraApplication: AuroraApplication): List<JsonNode>? {
        return auroraApplication.template?.let {
            val template = openShiftClient.get("template", it.template, "openshift")?.body as ObjectNode
            openShiftTemplateProcessor.generateObjects(template,
                    it.parameters, auroraApplication)
        }
    }

    fun generateRoute(auroraApplication: AuroraApplication, labels: Map<String, String>): List<JsonNode>? {
        return auroraApplication.route?.route?.map {
            logger.debug("Route is {}", it)
            val routeParams = mapOf(
                    "name" to auroraApplication.name,
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

    private fun generateBuilds(auroraApplication: AuroraApplication, deployId: String): List<JsonNode>? {
        return auroraApplication.build?.let {
            val buildName = if (it.buildSuffix != null) {
                "${auroraApplication.name}-${it.buildSuffix}"
            } else {
                auroraApplication.name
            }

            val buildParams = mapOf(
                    "labels" to findLabels(auroraApplication, deployId, buildName),
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

    fun findLabels(auroraApplication: AuroraApplication, deployId: String, name: String = auroraApplication.name): Map<String, String> {
        val labels = mapOf(
                "app" to name,
                "updatedBy" to userDetailsProvider.getAuthenticatedUser().username.replace(":", "-"),
                "affiliation" to auroraApplication.affiliation,
                "booberDeployId" to deployId
        )
        return labels
    }

    fun findEnv(mounts: List<Mount>?, auroraApplication: AuroraApplication): Map<String, String> {
        val mountEnv = mounts?.map {
            "VOLUME_${it.mountName.toUpperCase().replace("-", "_")}" to it.path
        }?.toMap() ?: mapOf()

        val splunkIndex = auroraApplication.deploy?.splunkIndex?.let { "SPLUNK_INDEX" to it }

        val certEnv = auroraApplication.deploy?.certificateCn?.let {
            val baseUrl = "/u01/secrets/app/${auroraApplication.name}-cert"
            mapOf(
                    "STS_CERTIFICATE_URL" to "$baseUrl/certificate.crt",
                    "STS_PRIVATE_KEY_URL" to "$baseUrl/privatekey.key",
                    "STS_KEYSTORE_DESCRIPTOR" to "$baseUrl/descriptor.properties"
            )
        } ?: mapOf()

        val debugEnv = auroraApplication.deploy?.flags?.takeIf { it.debug }?.let {
            mapOf(
                    "REMOTE_DEBUG" to "true",
                    "DEBUG_PORT" to "5005"
            )
        } ?: mapOf()

        val routeName = auroraApplication.route?.route?.takeIf { it.isNotEmpty() }?.first()?.let {
            val host = it.host ?: "${auroraApplication.name}-${auroraApplication.namespace}"
            "ROUTE_NAME" to "http://$host.${auroraApplication.cluster}.paas.skead.no${it.path ?: ""}"
        }

        val dbEnv = auroraApplication.deploy?.database?.takeIf { it.isNotEmpty() }?.let {
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
                "OPENSHIFT_CLUSTER" to auroraApplication.cluster,
                "HTTP_PORT" to "8080",
                "MANAGEMENT_HTTP_PORT" to "8081",
                "APP_NAME" to auroraApplication.name
        ).addIfNotNull(splunkIndex).addIfNotNull(routeName) + certEnv + debugEnv + dbEnv + mountEnv
    }

    fun findMounts(auroraApplication: AuroraApplication): List<Mount> {
        val mounts: List<Mount> = auroraApplication.volume?.mounts?.map {
            if (it.exist) {
                it
            } else {
                it.copy(volumeName = it.volumeName.ensureStartWith(auroraApplication.name, "-"))
            }
        } ?: emptyList()


        val configMount = auroraApplication.volume?.config?.let {

            Mount(path = "/u01/config/configmap",
                    type = MountType.ConfigMap,
                    volumeName = auroraApplication.name,
                    mountName = "config",
                    exist = false,
                    content = it)
        }

        val secretMount = auroraApplication.volume?.secrets?.let {
            Mount(path = "/u01/config/secret",
                    type = MountType.Secret,
                    volumeName = auroraApplication.name,
                    mountName = "secrets",
                    exist = false,
                    content = it)
        }

        val certMount = auroraApplication.deploy?.certificateCn?.let {
            Mount(path = "/u01/secrets/app/${auroraApplication.name}-cert",
                    type = MountType.Secret,
                    volumeName = "${auroraApplication.name}-cert",
                    mountName = "${auroraApplication.name}-cert",
                    exist = true,
                    content = null)
            //TODO: Add sprocket content here
        }

        val databaseMounts = auroraApplication.deploy?.database?.map {
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
        val mergedResult = sw.toString()
        val jsonResult = mapper.readTree(mergedResult)

        File("./build/$template").writeText(mapper.writeValueAsString(jsonResult))

        return jsonResult
    }


    fun generateImageStreamImport(name: String, docker: String): JsonNode {

        return mergeVelocityTemplate("imagestreamimport.json", mapOf("name" to name, "docker" to docker))
    }
}