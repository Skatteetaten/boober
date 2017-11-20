package no.skatteetaten.aurora.boober.service.internal.openshiftobjectgenerator

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.service.VelocityTemplateJsonService
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.ensureStartWith
import org.apache.commons.lang.StringEscapeUtils

class DeploymentConfigGenerator(val mapper: ObjectMapper, private val velocityTemplateJsonService: VelocityTemplateJsonService) {

    fun create(auroraDeploymentSpec: AuroraDeploymentSpec, labels: Map<String, String>, mounts: List<Mount>?): JsonNode? {

        return auroraDeploymentSpec.deploy?.let {
            val template = when (auroraDeploymentSpec.deploy.applicationPlatform) {
                ApplicationPlatform.java -> "deployment-config.json"
                ApplicationPlatform.web -> "deployment-config-web.json"
            }

            val annotations = mapOf(
                    "boober.skatteetaten.no/applicationFile" to it.applicationFile,
                    "console.skatteetaten.no/alarm" to it.flags.alarm.toString()
            )

            val cert = it.certificateCn?.takeIf { it.isNotBlank() }?.let {
                "sprocket.sits.no/deployment-config.certificate" to it
            }

            val overrides = StringEscapeUtils.escapeJavaScript(mapper.writeValueAsString(it.overrideFiles)).takeIf { it != "{}" }?.let {
                "boober.skatteetaten.no/overrides" to it
            }

            val managementPath = it.managementPath?.takeIf { it.isNotBlank() }?.let {
                "console.skatteetaten.no/management-path" to it
            }

            val release = it.releaseTo?.takeIf { it.isNotBlank() }

            val releaseToAnnotation = release?.let {
                "boober.skatteetaten.no/releaseTo" to it
            }
            val env = findEnv(mounts, auroraDeploymentSpec)

            val deployTag = release?.let {
                it
            } ?: it.version
            val tag = if (auroraDeploymentSpec.type == TemplateType.development) {
                "latest"
            } else {
                "default"
            }


            val pauseLabel = if (auroraDeploymentSpec.deploy.flags.pause) {
                "paused" to "true"
            } else null

            val dcLabels = labels + mapOf("name" to auroraDeploymentSpec.name, "deployTag" to deployTag).addIfNotNull(pauseLabel)
            val params = mapOf(
                    "annotations" to annotations
                            .addIfNotNull(releaseToAnnotation)
                            .addIfNotNull(overrides)
                            .addIfNotNull(managementPath)
                            .addIfNotNull(cert),
                    "labels" to dcLabels,
                    "name" to auroraDeploymentSpec.name,
                    "deploy" to it,
                    "mounts" to mounts,
                    "env" to env,
                    "imageStreamTag" to tag
            )

            velocityTemplateJsonService.renderToJson(template, params)
        }
    }

    fun findEnv(mounts: List<Mount>?, auroraDeploymentSpec: AuroraDeploymentSpec): Map<String, String> {
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

        return mapOf(
                "OPENSHIFT_CLUSTER" to auroraDeploymentSpec.cluster,
                "HTTP_PORT" to "8080",
                "MANAGEMENT_HTTP_PORT" to "8081",
                "APP_NAME" to auroraDeploymentSpec.name
        ).addIfNotNull(splunkIndex) + routeName + certEnv + debugEnv + dbEnv + mountEnv
    }
}