package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.model.AuroraApplication
import no.skatteetaten.aurora.boober.model.AuroraApplicationConfig
import no.skatteetaten.aurora.boober.model.Mount
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
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


    fun generateObjects(auroraApplication: AuroraApplication, deployId: String): LinkedList<JsonNode> {


        val overrides = auroraApplication.deploy?.let {
            StringEscapeUtils.escapeJavaScript(mapper.writeValueAsString(it.overrideFiles))
        }


        val database = auroraApplication.deploy?.database?.map { it.spec }?.joinToString(",") ?: ""

        logger.debug("Database is $database")
        //In use in velocity template
        val routeName = auroraApplication.route?.let {
            if (it.route.isEmpty()) {
                null
            } else {
                it.route.first().let {
                    val host = it.host ?: "${auroraApplication.name}-${auroraApplication.namespace}"
                    "http://$host.${auroraApplication.cluster}.paas.skead.no${it.path ?: ""}"
                }
            }
        }


        val buildName = auroraApplication.build?.let {
            if (it.buildSuffix != null) {
                "${auroraApplication.name}-${it.buildSuffix}"
            } else {
                auroraApplication.name
            }
        }

        val mounts: List<Mount>? = auroraApplication.volume?.mounts?.map {
            if (it.exist) {
                it
            } else {
                it.copy(volumeName = it.volumeName.ensureStartWith(auroraApplication.name, "-"))
            }
        }
        val params = mapOf(
                "overrides" to overrides,
                "deployId" to deployId,
                "aac" to auroraApplication,
                "mounts" to mounts,
                "buildName" to buildName,
                "routeName" to routeName,
                "username" to userDetailsProvider.getAuthenticatedUser().username.replace(":", "-"),
                "database" to database,
                "dbPath" to "/u01/secrets/app",
                "certPath" to "/u01/secrets/app/${auroraApplication.name}-cert"
        )

        val templatesToProcess = LinkedList(mutableListOf(
                "project.json",
                "deployer-rolebinding.json",
                "imagebuilder-rolebinding.json",
                "imagepuller-rolebinding.json",
                "admin-rolebinding.json"))

        if (auroraApplication.permissions.view != null) {
            templatesToProcess.add("view-rolebinding.json")
        }

        auroraApplication.deploy?.let {
            templatesToProcess.add("deployment-config.json")
            templatesToProcess.add("service.json")
        }

        if(auroraApplication.volume?.config?.isNotEmpty() == true) {
            templatesToProcess.add("configmap.json")
        }

        auroraApplication.volume?.secrets?.let {
            templatesToProcess.add("secret.json")
        }

        auroraApplication.build?.let {
            templatesToProcess.add("build-config.json")
            if (it.testGitUrl != null) {
                templatesToProcess.add("jenkins-build-config.json")
            }
        }

        auroraApplication.deploy?.let {
            templatesToProcess.add("imagestream.json")
        }

        val openShiftObjects = LinkedList(templatesToProcess.map { mergeVelocityTemplate(it, params) })


        mounts?.filter { !it.exist }?.map {
            logger.debug("Create manual mount {}", it)
            val mountParams = mapOf(
                    "aac" to auroraApplication,
                    "mount" to it,
                    "deployId" to deployId,
                    "username" to userDetailsProvider.getAuthenticatedUser().username.replace(":", "-")
            )
            mergeVelocityTemplate("mount.json", mountParams)
        }?.let {
            openShiftObjects.addAll(it)
        }

        auroraApplication.route?.route?.map {
            logger.debug("Route is {}", it)
            val routeParams = mapOf(
                    "aac" to auroraApplication,
                    "route" to it,
                    "deployId" to deployId,
                    "username" to userDetailsProvider.getAuthenticatedUser().username.replace(":", "-"))
            mergeVelocityTemplate("route.json", routeParams)
        }?.let {
            openShiftObjects.addAll(it)
        }

        auroraApplication.template?.let {
            val template = openShiftClient.get("template", it.template, "openshift")?.body as ObjectNode
            val generateObjects = openShiftTemplateProcessor.generateObjects(template,
                    it.parameters, auroraApplication)
            openShiftObjects.addAll(generateObjects)
        }

        auroraApplication.localTemplate?.let {
            val generateObjects = openShiftTemplateProcessor.generateObjects(it.templateJson as ObjectNode,
                    it.parameters, auroraApplication)
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

    fun generateImageStreamImport(name: String, docker: String): JsonNode {

        return mergeVelocityTemplate("imagestreamimport.json", mapOf("name" to name, "docker" to docker))
    }
}