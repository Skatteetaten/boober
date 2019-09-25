package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.resources
import io.fabric8.kubernetes.api.model.*
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentCommand
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeployment
import no.skatteetaten.aurora.boober.service.AuroraResource
import no.skatteetaten.aurora.boober.service.Feature
import no.skatteetaten.aurora.boober.service.OpenShiftObjectLabelService
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.filterNullValues
import org.springframework.stereotype.Service

fun AuroraDeploymentSpec.quantity(resource: String, classifier: String): Pair<String, Quantity> = resource to QuantityBuilder().withAmount(this["resources/$resource/$classifier"]).build()

val AuroraDeploymentSpec.splunkIndex: String? get() = this.getOrNull<String>("splunkIndex")

fun Map<String, String>.toEnvVars(): List<EnvVar> = this
        .mapKeys { it.key.replace(".", "_").replace("-", "_") }
        .map {
            EnvVarBuilder().withName(it.key).withValue(it.value).build()
        }

@Service
class DeploymentConfigFeature() : Feature {
    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraDeploymentCommand): Set<AuroraConfigFieldHandler> {

        return setOf(
                AuroraConfigFieldHandler("management", defaultValue = true, canBeSimplifiedConfig = true),
                AuroraConfigFieldHandler("management/path", defaultValue = "actuator"),
                AuroraConfigFieldHandler("management/port", defaultValue = "8081"),
                AuroraConfigFieldHandler("releaseTo"),
                AuroraConfigFieldHandler("alarm", defaultValue = true),
                AuroraConfigFieldHandler("pause", defaultValue = false),
                AuroraConfigFieldHandler("resources/cpu/min", defaultValue = "10m"),
                AuroraConfigFieldHandler("resources/cpu/max", defaultValue = "2000m"),
                AuroraConfigFieldHandler("resources/memory/min", defaultValue = "128Mi"),
                AuroraConfigFieldHandler("resources/memory/max", defaultValue = "512Mi"),
                AuroraConfigFieldHandler("splunkIndex"),
                AuroraConfigFieldHandler("debug", defaultValue = false),
                header.versionHandler)
    }

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, cmd: AuroraDeploymentCommand) {
        val dcLabels = createDcLabels(adc)
        val dcAnnotations = createDcAnnotations(adc, cmd)
        val envVars = createEnvVars(adc).toEnvVars()
        resources.forEach {
            if (it.resource.kind == "ApplicationDeployment") {
                val ad: ApplicationDeployment = jacksonObjectMapper().convertValue(it.resource)
                val spec = ad.spec
                spec.splunkIndex = adc.splunkIndex
                spec.releaseTo = adc.releaseTo
                spec.deployTag = adc.dockerTag
                spec.managementPath = adc.managementPath

            } else if (it.resource.kind == "DeploymentConfig") {
                val dc: DeploymentConfig = jacksonObjectMapper().convertValue(it.resource)

                if (dc.spec.template.metadata == null) {
                    dc.spec.template.metadata = ObjectMeta()
                }

                dc.spec.template.metadata.labels = dc.spec.template.metadata.labels?.addIfNotNull(dcLabels) ?: dcLabels
                dc.metadata.labels = it.resource.metadata.labels?.addIfNotNull(dcLabels) ?: dcLabels
                dc.metadata.annotations = it.resource.metadata.annotations?.addIfNotNull(dcAnnotations) ?: dcAnnotations

                if (adc["pause"]) {
                    dc.spec.replicas = 0
                }
                dc.spec.template.spec.containers.forEach { container ->
                    container.env.addAll(envVars)
                    container.resources {
                        requests = mapOf(adc.quantity("cpu", "min"), adc.quantity("memory", "min"))
                        limits = mapOf(adc.quantity("cpu", "max"), adc.quantity("memory", "max"))
                    }
                }
            }
        }
    }

    fun createEnvVars(adc: AuroraDeploymentSpec): Map<String, String> {

        val debugEnv = if (adc["debug"]) {
            mapOf(
                    "ENABLE_REMOTE_DEBUG" to "true",
                    "DEBUG_PORT" to "5005"
            )
        } else null

        return mapOf(
                "OPENSHIFT_CLUSTER" to adc["cluster"],
                "APP_NAME" to adc.name,
                "SPLUNK_INDEX" to adc.splunkIndex
        ).addIfNotNull(debugEnv).filterNullValues()

    }

    fun createDcLabels(adc: AuroraDeploymentSpec): Map<String, String> {

        val pauseLabel = if (adc["pause"]) {
            "paused" to "true"
        } else null

        val allLabels = mapOf("deployTag" to adc.dockerTag).addIfNotNull(pauseLabel)
        return OpenShiftObjectLabelService.toOpenShiftLabelNameSafeMap(allLabels)
    }

    fun createDcAnnotations(adc: AuroraDeploymentSpec, cmd: AuroraDeploymentCommand): Map<String, String> {

        fun escapeOverrides(): String? {
            val files =
                    cmd.overrideFiles.mapValues { jacksonObjectMapper().readValue(it.value, JsonNode::class.java) }
            val content = jacksonObjectMapper().writeValueAsString(files)
            return content.takeIf { it != "{}" }
        }

        return mapOf(
                "boober.skatteetaten.no/applicationFile" to cmd.applicationFile.name,
                "console.skatteetaten.no/alarm" to adc["alarm"],
                "boober.skatteetaten.no/overrides" to escapeOverrides(),
                "console.skatteetaten.no/management-path" to adc.managementPath,
                "boober.skatteetaten.no/releaseTo" to adc.releaseTo
        ).filterNullValues().filterValues { !it.isBlank() }
    }
}