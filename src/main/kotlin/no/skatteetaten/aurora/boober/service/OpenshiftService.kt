package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.model.Result
import org.apache.velocity.VelocityContext
import org.apache.velocity.app.VelocityEngine
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.StringWriter

@Service
class OpenshiftService(@Value("\${openshift.url}") val url: String, val ve: VelocityEngine) {

    fun templateExist(token: String, template: String): Boolean {
        //TODO GET request to openshift with token to check if template exist in Openshift namespace
        return true

    }

    fun findUsername(token: String): String {
        //TODO get call to cluster find username for bearer token
        return token
    }

    fun execute(res: Result, token: String): Result {

        val config = res.config!!
        //TODO here we need to switch on config.schemaVersion if we change the schema



        val routeName = "foo"

        val app = TemplateApp(config.name, config.affiliation, config.type.name, config.replicas, config.deploy?.splunkIndex ?: "", config.flags?.contains("route") ?: false, routeName, findUsername(token))
        val svc = TemplateService(config.deploy?.websealRoute ?: "",
                config.deploy?.websealRoles ?: "",
                config.deploy?.prometheus ?: true,
                config.deploy?.prometheusPath ?: "",
                config.deploy?.prometheusPort ?: 8080)
        val docker = TemplateDocker("docker-registry.aurora.sits.no:5000", config.build.version)
        val dc = TemplateDc(config.deploy?.managementPath ?: "", config.deploy?.alarm ?: true, config.cert ?: "", config.deploy?.database ?: "")
        val resources = TemplateResources(TemplateResourceFields("128Mi", config.deploy?.cpuRequest ?: ""), TemplateResourceFields(config.deploy?.maxMemory ?: "", "2000m"))



        val params =  mapOf("app" to app)



        val openshiftObjects = mapOf(
                "configmap" to ve.parse("configmap.json", params.plus("config" to config.configLine)),
                "route" to ve.parse("route.json", params),
                "service" to ve.parse("service.json",params.plus("service" to svc)),
                "imagestream" to ve.parse("imagestream.json", params.plus("docker" to docker)),
                "dc" to ve.parse("deployment-config.json", params.plus(listOf("docker" to docker, "resources" to resources, "dc" to dc)))

        )
        return res.copy(openshiftObjects = openshiftObjects)

    }


}

data class TemplateDocker(val registry: String, val tag: String, val istag: String = "default")
data class TemplateApp(val name: String, val affiliation: String, val type: String, val replicas: Int, val splunk: String, val route: Boolean, val routeName: String, val username: String)
data class TemplateService(val websealPrefix: String = "", val websealRoles: String = "", val prometheusEnabled: Boolean = true, val prometheusPath: String = "", val prometheusPort: Int = 8080)
data class TemplateDc(val managementPath: String = "", val alarm: Boolean = true, val certificateCn: String = "", val database: String = "")
data class TemplateResourceFields(val memory: String, val cpu: String)

data class TemplateResources(val request: TemplateResourceFields, val limits: TemplateResourceFields)

fun VelocityEngine.parse(template: String, content: Map<String, Any>): JsonNode {
    val context = VelocityContext()
    content.forEach { context.put(it.key, it.value) }
    val t = this.getTemplate("templates/$template.vm")
    val sw = StringWriter()
    t.merge(context, sw)
    return jacksonObjectMapper().readTree(sw.toString())
}