package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.resources
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.EnvVarBuilder
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.QuantityBuilder
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeployment
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.allNonSideCarContainers
import no.skatteetaten.aurora.boober.utils.ensureStartWith
import no.skatteetaten.aurora.boober.utils.filterNullValues
import no.skatteetaten.aurora.boober.utils.normalizeLabels
import org.springframework.stereotype.Service

fun AuroraDeploymentSpec.quantity(resource: String, classifier: String): Pair<String, Quantity?> {
    val field = this.getOrNull<String>("resources/$resource/$classifier")

    return resource to field?.let {
        QuantityBuilder().withAmount(it).build()
    }
}

val AuroraDeploymentSpec.splunkIndex: String? get() = this.getOrNull<String>("splunkIndex")

fun Map<String, String>.toEnvVars(): List<EnvVar> = this
    .mapKeys { it.key.replace(".", "_").replace("-", "_") }
    .map {
        EnvVarBuilder().withName(it.key).withValue(it.value).build()
    }

val AuroraDeploymentSpec.pause: Boolean get() = this["pause"]

val AuroraDeploymentSpec.managementPath
    get() = this.featureEnabled("management") {
        val path = this.get<String>("$it/path").ensureStartWith("/")
        val port = this.get<Int>("$it/port").toString().ensureStartWith(":")
        "$port$path"
    }

@Service
class DeploymentConfigFeature : Feature {
    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {

        val templateSpecificHeaders = if (header.type.completelyGenerated) {
            setOf(
                header.versionHandler,
                AuroraConfigFieldHandler("resources/cpu/min", defaultValue = "10m"),
                AuroraConfigFieldHandler("resources/cpu/max", defaultValue = "2000m"),
                AuroraConfigFieldHandler("resources/memory/min", defaultValue = "128Mi"),
                AuroraConfigFieldHandler("resources/memory/max", defaultValue = "512Mi")
            )
        } else {
            setOf(
                header.versionHandler,
                AuroraConfigFieldHandler("resources/cpu/min"),
                AuroraConfigFieldHandler("resources/cpu/max"),
                AuroraConfigFieldHandler("resources/memory/min"),
                AuroraConfigFieldHandler("resources/memory/max")
            )
        }
        return setOf(
            AuroraConfigFieldHandler("management", defaultValue = true, canBeSimplifiedConfig = true),
            AuroraConfigFieldHandler("management/path", defaultValue = "actuator"),
            AuroraConfigFieldHandler("management/port", defaultValue = "8081"),
            AuroraConfigFieldHandler("releaseTo"),
            AuroraConfigFieldHandler("alarm", defaultValue = true),
            AuroraConfigFieldHandler("pause", defaultValue = false),
            AuroraConfigFieldHandler("splunkIndex"),
            AuroraConfigFieldHandler("debug", defaultValue = false)
        ).addIfNotNull(templateSpecificHeaders)
    }

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, cmd: AuroraContextCommand) {
        val dcLabels = createDcLabels(adc)
        val dcAnnotations = createDcAnnotations(adc, cmd)
        val envVars = createEnvVars(adc).toEnvVars()
        resources.forEach {
            if (it.resource.kind == "ApplicationDeployment") {
                modifyResource(it, "Added information from deployment")
                val ad: ApplicationDeployment = jacksonObjectMapper().convertValue(it.resource)
                val spec = ad.spec
                spec.splunkIndex = adc.splunkIndex
                spec.releaseTo = adc.releaseTo
                spec.deployTag = adc.dockerTag
                spec.managementPath = adc.managementPath
            } else if (it.resource.kind == "DeploymentConfig") {
                val dc: DeploymentConfig = jacksonObjectMapper().convertValue(it.resource)

                modifyResource(it, "Added labels, annotations, shared env vars and request limits")
                if (dc.spec.template.metadata == null) {
                    dc.spec.template.metadata = ObjectMeta()
                }

                dc.spec.template.metadata.labels = dc.spec.template.metadata.labels?.addIfNotNull(dcLabels) ?: dcLabels
                dc.metadata.labels = it.resource.metadata.labels?.addIfNotNull(dcLabels) ?: dcLabels
                dc.metadata.annotations = it.resource.metadata.annotations?.addIfNotNull(dcAnnotations) ?: dcAnnotations

                if (adc.pause) {
                    dc.spec.replicas = 0
                }
                dc.allNonSideCarContainers.forEach { container ->
                    container.env.addAll(envVars)
                    container.resources {
                        requests = mapOf(adc.quantity("cpu", "min"), adc.quantity("memory", "min")).filterNullValues()
                        limits = mapOf(adc.quantity("cpu", "max"), adc.quantity("memory", "max")).filterNullValues()
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

        val pauseLabel = if (adc.pause) {
            "paused" to "true"
        } else null

        return mapOf("deployTag" to adc.dockerTag).addIfNotNull(pauseLabel).normalizeLabels()
    }

    fun createDcAnnotations(adc: AuroraDeploymentSpec, cmd: AuroraContextCommand): Map<String, String> {

        fun escapeOverrides(): String? {
            val files =
                cmd.overrideFiles.mapValues { jacksonObjectMapper().readValue(it.value, JsonNode::class.java) }
            val content = jacksonObjectMapper().writeValueAsString(files)
            return content.takeIf { it != "{}" }
        }

        // TODO: None of these are needed anymore. They are in AD file
        return mapOf(
            "boober.skatteetaten.no/applicationFile" to cmd.applicationFile.name,
            "console.skatteetaten.no/alarm" to adc["alarm"],
            "boober.skatteetaten.no/overrides" to escapeOverrides(),
            "console.skatteetaten.no/management-path" to adc.managementPath,
            "boober.skatteetaten.no/releaseTo" to adc.releaseTo
        ).filterNullValues().filterValues { !it.isBlank() }
    }
}
