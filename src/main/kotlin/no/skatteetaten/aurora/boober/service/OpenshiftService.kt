package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfig
import no.skatteetaten.aurora.boober.model.Result
import no.skatteetaten.aurora.boober.model.TemplateType
import org.apache.velocity.VelocityContext
import org.apache.velocity.app.VelocityEngine
import org.springframework.stereotype.Service
import java.io.StringWriter

@Service
class OpenshiftService(val ve: VelocityEngine) {

    fun templateExist(token: String, template: String): Boolean {
        //TODO GET request to openshift with token to check if template exist in Openshift namespace
        return true

    }


    fun findUsername(token: String): String {
        return token
    }


    fun generateObjects(config: AuroraDeploymentConfig, token: String): Map<String, JsonNode> {

        //TODO This is the code that uses the default that was set in the old AOC, that is the template.
        //TODO If we get an unified interface in here we do not have to do this here. We can always move it out.
        //TODO What should we store in git?

        val app = TemplateApp(
                config.name,
                config.namespace,
                config.affiliation,
                config.type.name,
                config.replicas,
                config.deploy.splunkIndex,
                config.route,
                config.rolling,
                config.routeName,
                findUsername(token)
        )

        val svc = TemplateService(config.deploy.websealRoute,
                config.deploy.websealRoles,
                config.deploy.prometheus,
                config.deploy.prometheusPath,
                config.deploy.prometheusPort
        )

        val docker = TemplateDocker(
                "docker-registry.aurora.sits.no:5000",
                config.build.version,
                config.dockerName,
                config.dockerGroup,
                config.deploy.tag,
                config.build.extraTags,
                TemplateImage("leveransepakkebygger", "prod"),
                TemplateImage("oracle8", "1")
        )

        val dc = TemplateDc(
                config.deploy.managementPath,
                config.deploy.alarm,
                config.cert,
                config.deploy.database,
                config.deploy.debug)

        val resources = TemplateResources(
                TemplateResourceFields("128Mi", config.deploy.cpuRequest),
                TemplateResourceFields(config.deploy.maxMemory, "2000m"))

        val params = mapOf("app" to app)

        val paramsWithDocker = params.plus("docker" to docker)
        val openshiftObjects = mutableMapOf(
                "projects" to ve.parse("project.json", params),
                "configmaps" to ve.parse("configmap.json", params.plus("appConfig" to config.configLine)),
                "services" to ve.parse("service.json", params.plus("service" to svc)),
                "imagestreams" to ve.parse("imagestream.json", paramsWithDocker),
                "deploymentconfigs" to ve.parse("deployment-config.json", paramsWithDocker.plus(listOf("resources" to resources, "dc" to dc)))
        )

        if (app.route) {
            openshiftObjects.put("routes", ve.parse("route.json", params))
        }

        if (config.type == TemplateType.development) {
            openshiftObjects.put("buildconfigs", ve.parse("build-config.json", paramsWithDocker))

        }
        return openshiftObjects
    }
}

data class TemplateImage(val name: String, val version: String)

data class TemplateDocker(val registry: String,
                          val tag: String,
                          val name: String,
                          val group: String,
                          val istag: String,
                          val extraTags: String,
                          val builder: TemplateImage,
                          val base: TemplateImage)

data class TemplateApp(val name: String,
                       val namespace: String,
                       val affiliation: String,
                       val type: String,
                       val replicas: Int,
                       val splunk: String,
                       val route: Boolean,
                       val rolling: Boolean,
                       val routeName: String,
                       val username: String)


data class TemplateService(val websealPrefix: String,
                           val websealRoles: String,
                           val prometheusEnabled: Boolean,
                           val prometheusPath: String,
                           val prometheusPort: Int)

data class TemplateDc(val managementPath: String
                      , val alarm: Boolean
                      , val certificateCn: String
                      , val database: String
                      , val debug: Boolean)

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