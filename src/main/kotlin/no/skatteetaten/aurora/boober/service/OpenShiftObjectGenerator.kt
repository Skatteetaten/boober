package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfig
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfigDeploy
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfigProcess
import no.skatteetaten.aurora.boober.model.TemplateType
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

    fun generateObjects(auroraDc: AuroraDeploymentConfig, deployId: String): LinkedList<JsonNode> {


        var overrides = StringEscapeUtils.escapeJavaScript(mapper.writeValueAsString(auroraDc.overrideFiles))


        val configs: Map<String, String> = auroraDc.config?.map { (key, value) ->
            val keyProps = if (!key.endsWith(".properties")) {
                "$key.properties"
            } else key

            keyProps to value.map {
                "${it.key}=${it.value}"
            }.joinToString(separator = "\\n")
        }?.toMap() ?: mapOf()


        val database = if (auroraDc is AuroraDeploymentConfigDeploy) {
            auroraDc.database.map { it.spec }.joinToString(",")
        } else ""

        logger.debug("Database is $database")

        val params = mapOf(
                "overrides" to overrides,
                "deployId" to deployId,
                "adc" to (auroraDc as? AuroraDeploymentConfigDeploy ?: auroraDc as AuroraDeploymentConfigProcess),
                "configs" to configs,
                "username" to userDetailsProvider.getAuthenticatedUser().username,
                "dockerRegistry" to dockerRegistry,
                "builder" to mapOf("name" to "leveransepakkebygger", "version" to "prod"),
                "base" to mapOf("name" to "oracle8", "version" to "1"),
                "database" to database,
                "dbPath" to "/u01/secrets/app",
                "certPath" to "/u01/secrets/app/${auroraDc.name}-cert"
        )

        val templatesToProcess = LinkedList(mutableListOf(
                "project.json",
                "admin-rolebinding.json"))

        if (auroraDc.permissions.view != null) {
            templatesToProcess.add("view-rolebinding.json")
        }

        if (auroraDc.type in listOf(TemplateType.deploy, TemplateType.development)) {
            templatesToProcess.add("deployment-config.json")
            templatesToProcess.add("service.json")
        }

        if (configs.isNotEmpty()) {
            templatesToProcess.add("configmap.json")
        }

        auroraDc.secrets?.let {
            templatesToProcess.add("secret.json")
        }

        if (auroraDc.route != null) {
            logger.debug("Route is {}", auroraDc.route)
            templatesToProcess.add("route.json")
        }

        if (auroraDc.type == TemplateType.development) {
            templatesToProcess.add("build-config.json")
        }

        if (auroraDc.type in listOf(TemplateType.deploy, TemplateType.development)) {
            val adc = auroraDc as? AuroraDeploymentConfigDeploy
            templatesToProcess.add("imagestream.json")
        }

        var openShiftObjects = LinkedList(templatesToProcess.map { mergeVelocityTemplate(it, params) })

        auroraDc.mounts?.filter { !it.exist }?.map {
            logger.debug("Create manual mount {}", it)
            val mountParams = mapOf("adc" to auroraDc, "mount" to it, "deployId" to deployId)
            mergeVelocityTemplate("mount.json", mountParams)
        }?.let {
            openShiftObjects.addAll(it)
        }

        if (auroraDc is AuroraDeploymentConfigProcess) {
            val generateObjects = openShiftTemplateProcessor.generateObjects(auroraDc)
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