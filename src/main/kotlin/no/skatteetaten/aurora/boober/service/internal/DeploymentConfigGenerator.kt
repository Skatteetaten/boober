package no.skatteetaten.aurora.boober.service.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.model.TemplateType.development
import no.skatteetaten.aurora.boober.service.OpenShiftObjectLabelService
import no.skatteetaten.aurora.boober.service.VelocityTemplateJsonService
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.ensureStartWith
import org.apache.commons.lang.StringEscapeUtils

class DeploymentConfigGenerator(
        private val mapper: ObjectMapper,
        private val velocityTemplateJsonService: VelocityTemplateJsonService
) {

    fun create(auroraDeploymentSpec: AuroraDeploymentSpec, labels: Map<String, String>, mounts: List<Mount>?): JsonNode? {

        if (auroraDeploymentSpec.deploy == null) return null

        val params: Map<String, Any?> = createTemplateParams(auroraDeploymentSpec, labels, mounts)

        val template = when (auroraDeploymentSpec.deploy.applicationPlatform) {
            ApplicationPlatform.java -> "deployment-config.json"
            ApplicationPlatform.web -> "deployment-config-web.json"
        }
        return velocityTemplateJsonService.renderToJson(template, params)
    }

    private fun createTemplateParams(auroraDeploymentSpec: AuroraDeploymentSpec, commonLabels: Map<String, String>, mounts: List<Mount>?): Map<String, Any?> {

        val annotations = createAnnotations(auroraDeploymentSpec.deploy!!)
        val labels = createLabels(auroraDeploymentSpec, commonLabels)
        val envVars = createEnvVars(mounts, auroraDeploymentSpec)

        val tag = when (auroraDeploymentSpec.type) {
            development -> "latest"
            else -> "default"
        }

        val params = mapOf(
                "annotations" to annotations,
                "labels" to labels,
                "name" to auroraDeploymentSpec.name,
                "deploy" to auroraDeploymentSpec.deploy,
                "mounts" to mounts,
                "env" to envVars,
                "imageStreamTag" to tag
        )
        return params
    }

    private fun createAnnotations(deploy: AuroraDeploy): Map<String, String> {

        val annotations = mapOf(
                "boober.skatteetaten.no/applicationFile" to deploy.applicationFile,
                "console.skatteetaten.no/alarm" to deploy.flags.alarm.toString()
        )
        val overrides = StringEscapeUtils.escapeJavaScript(mapper.writeValueAsString(deploy.overrideFiles)).takeIf { it != "{}" }?.let {
            "boober.skatteetaten.no/overrides" to it
        }

        val cert = deploy.certificateCn?.withNonBlank { "sprocket.sits.no/deployment-config.certificate" to it }
        val managementPath = deploy.managementPath?.withNonBlank { "console.skatteetaten.no/management-path" to it }
        val releaseToAnnotation = deploy.releaseTo?.withNonBlank { "boober.skatteetaten.no/releaseTo" to it }

        return annotations
                .addIfNotNull(releaseToAnnotation)
                .addIfNotNull(overrides)
                .addIfNotNull(managementPath)
                .addIfNotNull(cert)
    }

    private fun createLabels(auroraDeploymentSpec: AuroraDeploymentSpec, labels: Map<String, String>): Map<String, String> {

        val deploy = auroraDeploymentSpec.deploy!!

        val deployTag = "deployTag" to (deploy.releaseTo?.withNonBlank { it } ?: deploy.version)
        val pauseLabel = if (deploy.flags.pause) {
            "paused" to "true"
        } else null

        val allLabels = labels + mapOf(
                "name" to auroraDeploymentSpec.name,
                deployTag
        ).addIfNotNull(pauseLabel)
        return OpenShiftObjectLabelService.toOpenShiftLabelNameSafeMap(allLabels)
    }

    private fun createEnvVars(mounts: List<Mount>?, auroraDeploymentSpec: AuroraDeploymentSpec): Map<String, String> {

        val mountEnv = mounts?.map {
            "VOLUME_${it.mountName.toUpperCase().replace("-", "_")}" to it.path
        }?.toMap() ?: mapOf()

        val splunkIndex = auroraDeploymentSpec.deploy?.splunkIndex?.let { "SPLUNK_INDEX" to it }

        val certEnv = auroraDeploymentSpec.deploy?.certificateCn?.let {
            val baseUrl = "/u01/secrets/app/${auroraDeploymentSpec.name}-cert"
            mapOf(
                    "STS_CERTIFICATE_URL" to "$baseUrl/certificate.crt",
                    "STS_PRIVATE_KEY_URL" to "$baseUrl/privatekey.key",
                    "STS_KEYSTORE_DESCRIPTOR" to "$baseUrl/descriptor.properties"
            )
        } ?: mapOf()

        val debugEnv = auroraDeploymentSpec.deploy?.flags?.takeIf { it.debug }?.let {
            mapOf(
                    "REMOTE_DEBUG" to "true",
                    "DEBUG_PORT" to "5005"
            )
        } ?: mapOf()

        val configEnv = auroraDeploymentSpec.deploy?.env ?: emptyMap()

        val routeName = auroraDeploymentSpec.route?.route?.takeIf { it.isNotEmpty() }?.first()?.let {
            val host = auroraDeploymentSpec.assembleRouteHost(it.host ?: auroraDeploymentSpec.name)

            val url = "$host${it.path?.ensureStartWith("/") ?: ""}"
            mapOf("ROUTE_NAME" to url, "ROUTE_URL" to "http://$url")
        } ?: mapOf()

        val dbEnv = auroraDeploymentSpec.deploy?.database?.takeIf { it.isNotEmpty() }?.let {
            fun createDbEnv(db: Database, envName: String): List<Pair<String, String>> {
                val path = "/u01/secrets/app/${db.name.toLowerCase()}-db"
                val envName = envName.replace("-", "_").toUpperCase()

                return listOf(
                        envName to "$path/info",
                        "${envName}_PROPERTIES" to "$path/db.properties"
                )
            }

            it.flatMap { createDbEnv(it, "${it.name}_db") } + createDbEnv(it.first(), "db")
        }?.toMap() ?: mapOf()

        val envs = mapOf(
                "OPENSHIFT_CLUSTER" to auroraDeploymentSpec.cluster,
                "HTTP_PORT" to "8080",
                "MANAGEMENT_HTTP_PORT" to "8081",
                "APP_NAME" to auroraDeploymentSpec.name
        ).addIfNotNull(splunkIndex) + routeName + certEnv + debugEnv + dbEnv + mountEnv + configEnv

        return envs.mapKeys { it.key.replace(".", "_").replace("-", "_") }
    }

    private inline fun <R> String.withNonBlank(block: (String) -> R?): R? {

        if (this.isBlank()) {
            return null
        }
        return block(this)
    }
}