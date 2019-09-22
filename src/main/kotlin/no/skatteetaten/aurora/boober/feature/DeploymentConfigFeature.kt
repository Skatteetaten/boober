package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.resources
import io.fabric8.kubernetes.api.model.*
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentContext
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.service.AuroraResource
import no.skatteetaten.aurora.boober.service.Feature
import no.skatteetaten.aurora.boober.service.OpenShiftObjectLabelService
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.filterNullValues
import org.springframework.stereotype.Service

fun AuroraDeploymentContext.quantity(resource: String, classifier: String): Pair<String, Quantity> = resource to QuantityBuilder().withAmount(this["resources/$resource/$classifier"]).build()

fun Map<String, String>.toEnvVars(): List<EnvVar> = this
        .mapKeys { it.key.replace(".", "_").replace("-", "_") }
        .map {
            EnvVarBuilder().withName(it.key).withValue(it.value).build()
        }

@Service
class DeploymentConfigFeature() : Feature {
    override fun handlers(header: AuroraDeploymentContext): Set<AuroraConfigFieldHandler> {
        //TODO: Do not like this consider field to see if version is required on templateType enum?
        val version = when (header.type) {
            in listOf(TemplateType.deploy, TemplateType.development) -> deployVersion
            in listOf(TemplateType.localTemplate, TemplateType.template) -> templateVersion
            else -> null
        }
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
                AuroraConfigFieldHandler("debug", defaultValue = false)
        ).addIfNotNull(version)
    }

    override fun modify(adc: AuroraDeploymentContext, resources: Set<AuroraResource>) {
        val dcLabels = createDcLabels(adc)
        val dcAnnotations = createDcAnnotations(adc)
        val envVars = createEnvVars(adc).toEnvVars()
        resources.forEach {
            if (it.resource.kind == "DeploymentConfig") {
                val dc: DeploymentConfig = jacksonObjectMapper().convertValue(it.resource)

                if (dc.spec.template.metadata == null) {
                    dc.spec.template.metadata = ObjectMeta()
                }

                dc.spec.template.metadata.labels = dc.spec.template.metadata.labels?.addIfNotNull(dcLabels) ?: dcLabels
                dc.metadata.labels = it.resource.metadata.labels?.addIfNotNull(dcLabels) ?: dcLabels
                dc.metadata.annotations = it.resource.metadata.annotations?.addIfNotNull(dcAnnotations) ?: dcAnnotations

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

    fun createEnvVars(adc: AuroraDeploymentContext): Map<String, String> {

        val debugEnv = if (adc["debug"]) {
            mapOf(
                    "ENABLE_REMOTE_DEBUG" to "true",
                    "DEBUG_PORT" to "5005"
            )
        } else null

        return mapOf(
                "OPENSHIFT_CLUSTER" to adc["cluster"],
                "APP_NAME" to adc.name,
                "SPLUNK_INDEX" to adc.getOrNull<String>("splunkIndex")
        ).addIfNotNull(debugEnv).filterNullValues()

    }

    fun createDcLabels(adc: AuroraDeploymentContext): Map<String, String> {

        val pauseLabel = if (adc["pause"]) {
            "paused" to "true"
        } else null

        val allLabels = mapOf("deployTag" to adc.dockerTag).addIfNotNull(pauseLabel)
        return OpenShiftObjectLabelService.toOpenShiftLabelNameSafeMap(allLabels)
    }

    fun createDcAnnotations(adc: AuroraDeploymentContext): Map<String, String> {

        fun escapeOverrides(): String? {
            val files =
                    adc.overrideFiles.mapValues { jacksonObjectMapper().readValue(it.value, JsonNode::class.java) }
            val content = jacksonObjectMapper().writeValueAsString(files)
            return content.takeIf { it != "{}" }
        }

        return mapOf(
                "boober.skatteetaten.no/applicationFile" to adc.applicationFile.name,
                "console.skatteetaten.no/alarm" to adc["alarm"],
                "boober.skatteetaten.no/overrides" to escapeOverrides(),
                "console.skatteetaten.no/management-path" to adc.managementPath,
                "boober.skatteetaten.no/releaseTo" to adc.releaseTo
        ).filterNullValues().filterValues { !it.isBlank() }
    }
}