package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.model.AuroraApplicationConfig
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
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
        val routeName = auroraApplicationConfig.route?.let {
            if (it.route.isEmpty()) {
                null
            } else {
                it.route.first().let {
                    val host = it.host ?: "${auroraApplicationConfig.name}-${auroraApplicationConfig.namespace}"
                    "http://$host.${auroraApplicationConfig.cluster}.paas.skead.no${it.path ?: ""}"
                }
            }
        }


        val buildName = auroraApplicationConfig.build?.let {
            if (it.buildSuffix != null) {
                "${auroraApplicationConfig.name}-${it.buildSuffix}"
            } else {
                auroraApplicationConfig.name
            }
        }
        val params = mapOf(
                "overrides" to overrides,
                "deployId" to deployId,
                "aac" to auroraApplicationConfig,
                "buildName" to buildName,
                "routeName" to routeName,
                "username" to userDetailsProvider.getAuthenticatedUser().username,
                "database" to database,
                "dbPath" to "/u01/secrets/app",
                "certPath" to "/u01/secrets/app/${auroraApplicationConfig.name}-cert"
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

        if(auroraApplicationConfig.volume?.config?.isNotEmpty() == true) {
            templatesToProcess.add("configmap.json")
        }

        auroraApplicationConfig.volume?.secrets?.let {
            templatesToProcess.add("secret.json")
        }

        auroraApplicationConfig.build?.let {
            templatesToProcess.add("build-config.json")
            if (it.testGitUrl != null) {
                templatesToProcess.add("jenkins-build-config.json")
            }
        }

        auroraApplicationConfig.deploy?.let {
            templatesToProcess.add("imagestream.json")
        }

        val openShiftObjects = LinkedList(templatesToProcess.map { mergeVelocityTemplate(it, params) })

        auroraApplicationConfig.volume?.mounts?.filter { !it.exist }?.map {
            logger.debug("Create manual mount {}", it)
            val mountParams = mapOf(
                    "aac" to auroraApplicationConfig,
                    "mount" to it,
                    "deployId" to deployId,
                    "username" to userDetailsProvider.getAuthenticatedUser().username
            )
            mergeVelocityTemplate("mount.json", mountParams)
        }?.let {
            openShiftObjects.addAll(it)
        }

        auroraApplicationConfig.route?.route?.map {
            logger.debug("Route is {}", it)
            val routeParams = mapOf(
                    "aac" to auroraApplicationConfig,
                    "route" to it,
                    "deployId" to deployId,
                    "username" to userDetailsProvider.getAuthenticatedUser().username)
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


    private fun mergeVelocityTemplate(template: String, content: Map<String, Any?>): JsonNode {
        val context = VelocityContext()
        content.forEach { context.put(it.key, it.value) }
        val t = ve.getTemplate("templates/$template.vm")
        val sw = StringWriter()
        t.merge(context, sw)
        return mapper.readTree(sw.toString())
    }
}