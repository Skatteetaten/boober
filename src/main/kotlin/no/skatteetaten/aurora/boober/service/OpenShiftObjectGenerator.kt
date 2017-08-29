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
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.StringWriter
import java.util.*

@Service
class OpenShiftObjectGenerator(
        val userDetailsProvider: UserDetailsProvider,
        val ve: VelocityEngine,
        val mapper: ObjectMapper,
        val openShiftTemplateProcessor: OpenShiftTemplateProcessor,
        val openShiftClient: OpenShiftResourceClient,
        @Value("\${boober.docker.registry}") val dockerRegistry: String) {

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


        val overrides = auroraApplicationConfig.dc?.let {
            StringEscapeUtils.escapeJavaScript(mapper.writeValueAsString(it.overrideFiles))
        }


        val database = auroraApplicationConfig.deploy?.database?.map { it.spec }?.joinToString(",") ?: ""

        logger.debug("Database is $database")

        val params = mapOf(
                "overrides" to overrides,
                "deployId" to deployId,
                "adc" to auroraApplicationConfig,
                "username" to userDetailsProvider.getAuthenticatedUser().username,
                "dockerRegistry" to dockerRegistry,
                "builder" to mapOf("name" to "leveransepakkebygger", "version" to "prod"),
                "base" to mapOf("name" to "oracle8", "version" to "1"),
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

        auroraApplicationConfig.dc?.config?.isNotEmpty().let {
            templatesToProcess.add("configmap.json")
        }

        auroraApplicationConfig.dc?.secrets?.let {
            templatesToProcess.add("secret.json")
        }

        auroraApplicationConfig.build?.let {
            templatesToProcess.add("build-config.json")
        }

        auroraApplicationConfig.deploy?.let {
            templatesToProcess.add("imagestream.json")
        }

        val openShiftObjects = LinkedList(templatesToProcess.map { mergeVelocityTemplate(it, params) })

        auroraApplicationConfig.dc?.mounts?.filter { !it.exist }?.map {
            logger.debug("Create manual mount {}", it)
            val mountParams = mapOf(
                    "adc" to auroraApplicationConfig,
                    "mount" to it,
                    "deployId" to deployId,
                    "username" to userDetailsProvider.getAuthenticatedUser().username
            )
            mergeVelocityTemplate("mount.json", mountParams)
        }?.let {
            openShiftObjects.addAll(it)
        }

        auroraApplicationConfig.dc?.route?.map {
            logger.debug("Route is {}", it)
            val routeParams = mapOf(
                    "adc" to auroraApplicationConfig,
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