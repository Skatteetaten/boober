package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import no.skatteetaten.aurora.boober.model.AuroraDeploy
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfig
import no.skatteetaten.aurora.boober.model.TemplateType
import org.apache.velocity.VelocityContext
import org.apache.velocity.app.VelocityEngine
import org.springframework.stereotype.Service
import java.io.StringWriter

@Service
class OpenShiftService(
        val ve: VelocityEngine,
        val mapper: ObjectMapper
) {

    fun templateExist(token: String, template: String): Boolean {
        //TODO GET request to openshift with token to check if template exist in Openshift namespace
        return true
    }


    fun findUsername(token: String): String {
        return token
    }


    fun generateObjects(auroraDc: AuroraDeploymentConfig, token: String): List<JsonNode> {

        //TODO This is the code that uses the default that was set in the old AOC, that is the template.
        //TODO If we get an unified interface in here we do not have to do this here. We can always move it out.
        //TODO What should we store in git?

        val deployDescriptor = auroraDc.deployDescriptor as AuroraDeploy

        val params = mapOf(
                "adc" to auroraDc,
                "dd" to deployDescriptor,
                "username" to findUsername(token),
                "dockerRegistry" to "docker-registry.aurora.sits.no:5000",
                "builder" to mapOf("name" to "leveransepakkebygger", "version" to "prod"),
                "base" to mapOf("name" to "oracle8", "version" to "1")
        )

        val templatesToProcess = mutableListOf(
                "project.json",
                "configmap.json",
                "service.json",
                "imagestream.json",
                "deployment-config.json"
        )

        if (auroraDc.route) {
            templatesToProcess.add("route.json")
        }

        if (auroraDc.type == TemplateType.development) {
            templatesToProcess.add("build-config.json")
        }
        return templatesToProcess.map { mergeVelocityTemplate(it, params) }
    }

    private fun mergeVelocityTemplate(template: String, content: Map<String, Any>): JsonNode {
        val context = VelocityContext()
        content.forEach { context.put(it.key, it.value) }
        val t = ve.getTemplate("templates/$template.vm")
        val sw = StringWriter()
        t.merge(context, sw)
        return mapper.readTree(sw.toString())
    }
}