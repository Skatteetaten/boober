package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.model.AuroraApplicationConfig
import no.skatteetaten.aurora.boober.model.Database
import no.skatteetaten.aurora.boober.model.Mount
import no.skatteetaten.aurora.boober.model.MountType
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

    fun generateObjects(auroraApplicationConfig: AuroraApplicationConfig, deployId: String): LinkedList<JsonNode> {


        val overrides = auroraApplicationConfig.deploy?.let {
            StringEscapeUtils.escapeJavaScript(mapper.writeValueAsString(it.overrideFiles))
        }


        val database = auroraApplicationConfig.deploy?.database?.map { it.spec }?.joinToString(",") ?: ""

        logger.debug("Database is $database")
        //In use in velocity template


        val mounts: List<Mount>? = findMounts(auroraApplicationConfig)

        val env = findEnv(mounts, auroraApplicationConfig)

        val labels = findLabels(auroraApplicationConfig, deployId, auroraApplicationConfig.name)

        val params = mapOf(
                "overrides" to overrides,
                "labels" to labels,
                "aac" to auroraApplicationConfig,
                "mounts" to mounts,
                "env" to env,
                "username" to userDetailsProvider.getAuthenticatedUser().username,
                "database" to database
        )

        val templatesToProcess = LinkedList(mutableListOf(
                "project.json",
                "deployer-rolebinding.json",
                "imagebuilder-rolebinding.json",
                "imagepuller-rolebinding.json",
                "admin-rolebinding.json"))

        if (auroraApplicationConfig.permissions.view != null) {
            templatesToProcess.add("view-rolebinding.json")
        }

        auroraApplicationConfig.deploy?.let {
            templatesToProcess.add("deployment-config.json")
            templatesToProcess.add("service.json")
        }

        auroraApplicationConfig.deploy?.let {
            templatesToProcess.add("imagestream.json")
        }

        val openShiftObjects = LinkedList(templatesToProcess.map {
            mergeVelocityTemplate(it, params)
        })


        auroraApplicationConfig.build?.let {
            val buildName = if (it.buildSuffix != null) {
                "${auroraApplicationConfig.name}-${it.buildSuffix}"
            } else {
                auroraApplicationConfig.name
            }

            val buildParams = mapOf(
                    "labels" to findLabels(auroraApplicationConfig, deployId, buildName),
                    "buildName" to buildName,
                    "build" to it
            )
            openShiftObjects.add(mergeVelocityTemplate("build-config.json", buildParams))

            if (it.testGitUrl != null) {
                openShiftObjects.add(mergeVelocityTemplate("jenkins-build-config.json", buildParams))
            }
        }

        mounts?.filter { !it.exist }?.map {
            logger.debug("Create manual mount {}", it)
            val mountParams = mapOf(
                    "mount" to it,
                    "labels" to labels
            )
            mergeVelocityTemplate("mount.json", mountParams)
        }?.let {
            openShiftObjects.addAll(it)
        }

        auroraApplicationConfig.route?.route?.map {
            logger.debug("Route is {}", it)
            val routeParams = mapOf(
                    "name" to auroraApplicationConfig.name,
                    "route" to it,
                    "labels" to labels)
            mergeVelocityTemplate("route.json", routeParams)
        }?.let {
            openShiftObjects.addAll(it)
        }

        auroraApplicationConfig.template?.let {
            val template = openShiftClient.get("template", it.template, "openshift")?.body as ObjectNode
            val generateObjects = openShiftTemplateProcessor.generateObjects(template,
                    it.parameters, auroraApplicationConfig)
            openShiftObjects.addAll(generateObjects)
        }

        auroraApplicationConfig.localTemplate?.let {
            val generateObjects = openShiftTemplateProcessor.generateObjects(it.templateJson as ObjectNode,
                    it.parameters, auroraApplicationConfig)
            openShiftObjects.addAll(generateObjects)
        }
        return openShiftObjects
    }

    fun findLabels(auroraApplicationConfig: AuroraApplicationConfig, deployId: String, name: String): Map<String, String> {
        val labels = mapOf(
                "app" to name,
                "updatedBy" to userDetailsProvider.getAuthenticatedUser().username,
                "affiliation" to auroraApplicationConfig.affiliation,
                "booberDeployId" to deployId
        )
        return labels
    }

    fun findEnv(mounts: List<Mount>?, auroraApplicationConfig: AuroraApplicationConfig): Map<String, String> {
        val mountEnv = mounts?.map {
            "VOLUME_${it.mountName.toUpperCase().replace("-", "_")}" to it.path
        }?.toMap() ?: mapOf()

        val splunkIndex = auroraApplicationConfig.deploy?.splunkIndex?.let { "SPLUNK_INDEX" to it }

        val certEnv = auroraApplicationConfig.deploy?.certificateCn?.let {
            val baseUrl = "/u01/secrets/app/${auroraApplicationConfig.name}-cert"
            mapOf(
                    "STS_CERTIFICATE_URL" to "$baseUrl/certificate.crt",
                    "STS_PRIVATE_KEY_URL" to "$baseUrl/privatekey.key",
                    "STS_KEYSTORE_DESCRIPTOR" to "$baseUrl/descriptor.properties"
            )
        } ?: mapOf()

        val debugEnv = auroraApplicationConfig.deploy?.flags?.takeIf { it.debug }?.let {
            mapOf(
                    "REMOTE_DEBUG" to "true",
                    "DEBUG_PORT" to "5005"
            )
        } ?: mapOf()

        val routeName = auroraApplicationConfig.route?.route?.takeIf { it.isNotEmpty() }?.first()?.let {
            val host = it.host ?: "${auroraApplicationConfig.name}-${auroraApplicationConfig.namespace}"
            "ROUTE_NAME" to "http://$host.${auroraApplicationConfig.cluster}.paas.skead.no${it.path ?: ""}"
        }

        val dbEnv = auroraApplicationConfig.deploy?.database?.takeIf { it.isNotEmpty() }?.let {
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
                "OPENSHIFT_CLUSTER" to auroraApplicationConfig.cluster,
                "HTTP_PORT" to "8080",
                "MANAGEMENT_HTTP_PORT" to "8081",
                "APP_NAME" to auroraApplicationConfig.name
        ).addIfNotNull(splunkIndex).addIfNotNull(routeName) + certEnv + debugEnv + dbEnv + mountEnv
    }

    fun findMounts(auroraApplicationConfig: AuroraApplicationConfig): List<Mount> {
        val mounts: List<Mount> = auroraApplicationConfig.volume?.mounts?.map {
            if (it.exist) {
                it
            } else {
                it.copy(volumeName = it.volumeName.ensureStartWith(auroraApplicationConfig.name, "-"))
            }
        } ?: emptyList()


        val configMount = auroraApplicationConfig.volume?.config?.let {

            Mount(path = "/u01/config/configmap",
                    type = MountType.ConfigMap,
                    volumeName = auroraApplicationConfig.name,
                    mountName = "config",
                    exist = false,
                    content = it)
        }

        val secretMount = auroraApplicationConfig.volume?.secrets?.let {
            Mount(path = "/u01/config/secret",
                    type = MountType.Secret,
                    volumeName = auroraApplicationConfig.name,
                    mountName = "secrets",
                    exist = false,
                    content = it)
        }

        val certMount = auroraApplicationConfig.deploy?.certificateCn?.let {
            Mount(path = "/u01/secrets/app/${auroraApplicationConfig.name}-cert",
                    type = MountType.Secret,
                    volumeName = "${auroraApplicationConfig.name}-cert",
                    mountName = "${auroraApplicationConfig.name}-cert",
                    exist = true,
                    content = null)
            //TODO: Add sprocket content here
        }

        val databaseMounts = auroraApplicationConfig.deploy?.database?.map {
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