package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfig
import no.skatteetaten.aurora.boober.model.AuroraObjectsConfig
import no.skatteetaten.aurora.boober.model.AuroraProcessConfig
import no.skatteetaten.aurora.boober.model.TemplateType
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
        val mapper: ObjectMapper
) {

    val logger: Logger = LoggerFactory.getLogger(OpenShiftObjectGenerator::class.java)

    fun generateBuildRequest(auroraDc: AuroraDeploymentConfig): JsonNode {
        logger.debug("Generating build request for name ${auroraDc.name}")
        return mergeVelocityTemplate("buildrequest.json", mapOf("adc" to auroraDc))

    }

    fun generateDeploymentRequest(auroraDc: AuroraObjectsConfig): JsonNode {
        logger.debug("Generating deploy request for name ${auroraDc.name}")
        return mergeVelocityTemplate("deploymentrequest.json", mapOf("adc" to auroraDc))

    }

    fun generateObjects(auroraDc: AuroraObjectsConfig): List<JsonNode> {

        val configs: Map<String, String> = auroraDc.config?.map { (key, value) ->
            key to value.map { "${it.key}=${it.value}" }.joinToString(separator = "\\n")
        }?.toMap() ?: mapOf()

        val params = mapOf(
                "adc" to (auroraDc as? AuroraDeploymentConfig ?: auroraDc as AuroraProcessConfig),
                "configs" to configs,
                "username" to userDetailsProvider.getAuthenticatedUser().username,
                "dockerRegistry" to "docker-registry.aurora.sits.no:5000",
                "builder" to mapOf("name" to "leveransepakkebygger", "version" to "prod"),
                "base" to mapOf("name" to "oracle8", "version" to "1")
        )

        val templatesToProcess = mutableListOf(
                "project.json",
                "rolebinding.json"
        )

        if (auroraDc.type != TemplateType.process) {
            templatesToProcess.add("deployment-config.json")
            templatesToProcess.add("service.json")
        }

        if (configs.isNotEmpty()) {
            templatesToProcess.add("configmap.json")
        }

        auroraDc.secrets?.let {
            templatesToProcess.add("secret.json")
        }

        if (auroraDc is AuroraDeploymentConfig && auroraDc.flags.route) {
            templatesToProcess.add("route.json")
        }

        if (auroraDc.type == TemplateType.development) {
            templatesToProcess.add("build-config.json")
        }

        if (auroraDc.type != TemplateType.process) {
            templatesToProcess.add("imagestream.json")
        }

        return templatesToProcess.map { mergeVelocityTemplate(it, params) }
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