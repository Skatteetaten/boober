package no.skatteetaten.aurora.boober.feature

import org.springframework.stereotype.Service
import com.fkorotkov.kubernetes.resources
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.EnvVarBuilder
import io.fabric8.kubernetes.api.model.LabelSelectorBuilder
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.TopologySpreadConstraint
import io.fabric8.kubernetes.api.model.TopologySpreadConstraintBuilder
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.findSubHandlers
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeployment
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.allNonSideCarContainers
import no.skatteetaten.aurora.boober.utils.disallowedPattern
import no.skatteetaten.aurora.boober.utils.boolean
import no.skatteetaten.aurora.boober.utils.convertValueToString
import no.skatteetaten.aurora.boober.utils.ensureStartWith
import no.skatteetaten.aurora.boober.utils.filterNullValues
import no.skatteetaten.aurora.boober.utils.normalizeLabels

const val ANNOTATION_BOOBER_DEPLOYTAG = "boober.skatteetaten.no/deployTag"

fun AuroraDeploymentSpec.quantity(resource: String, classifier: String): Pair<String, Quantity?> {
    val field = this.getOrNull<String>("resources/$resource/$classifier")

    return resource to field?.let {
        Quantity(it)
    }
}

val AuroraDeploymentSpec.splunkIndex: String? get() = this.getOrNull<String>("splunkIndex")

fun String.normalizeEnvVar(): String = this.replace(" ", "_").replace(".", "_").replace("-", "_")

fun Map<String, String>.toEnvVars(): List<EnvVar> = this
    .mapKeys { it.key.normalizeEnvVar() }
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

fun AuroraContextCommand.nodeSelectorHandlers(): Set<AuroraConfigFieldHandler> {
    val subHandlers = this.applicationFiles
        .findSubHandlers("nodeSelector")
        .toSet()
    return if (subHandlers.isNotEmpty()) {
        subHandlers.addIfNotNull(AuroraConfigFieldHandler("nodeSelector"))
    } else {
        emptySet()
    }
}

val AuroraDeploymentSpec.nodeSelector: Map<String, String>? get() = this.getOrNull<Map<String, Any?>>("nodeSelector")
    ?.map { k ->
        k.value?.let {
            v ->
            k.key to convertValueToString(v)
        } ?: run { k.key to "" }
    }
    ?.toMap()

@Service
class DeploymentConfigFeature() : Feature {

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {

        val templateSpecificHeaders = if (header.type.auroraGeneratedDeployment) {
            setOf(
                header.versionHandler,
                AuroraConfigFieldHandler("resources/cpu/min", defaultValue = "10m"),
                AuroraConfigFieldHandler("resources/cpu/max", defaultValue = "2000m"),
                AuroraConfigFieldHandler("resources/memory/min", defaultValue = "128Mi"),
                AuroraConfigFieldHandler("resources/memory/max", defaultValue = "512Mi"),
                AuroraConfigFieldHandler(
                    "management",
                    defaultValue = true,
                    canBeSimplifiedConfig = true,
                    validator = { it.boolean() }
                )
            )
        } else {
            setOf(
                header.versionHandler,
                AuroraConfigFieldHandler("resources/cpu/min"),
                AuroraConfigFieldHandler("resources/cpu/max"),
                AuroraConfigFieldHandler("resources/memory/min"),
                AuroraConfigFieldHandler("resources/memory/max"),
                AuroraConfigFieldHandler(
                    "management",
                    defaultValue = false,
                    canBeSimplifiedConfig = true,
                    validator = { it.boolean() }
                )
            )
        }
        return setOf(
            // TODO: some of these should not be there for type=job
            AuroraConfigFieldHandler("management/path", defaultValue = "actuator"),
            AuroraConfigFieldHandler("management/port", defaultValue = "8081"),
            AuroraConfigFieldHandler(
                "releaseTo",
                validator = {
                    it.disallowedPattern(
                        pattern = "^(\\d.*)|(latest\$)",
                        required = false,
                        message = "Disallowed value, neither semantic version nor latest are allowed values"
                    )
                }
            ),
            // TODO: dette alarm feltet blir ikke brukt til noe. Vi kan vel fjerne det?
            AuroraConfigFieldHandler("alarm", defaultValue = true, validator = { it.boolean() }),
            AuroraConfigFieldHandler("pause", defaultValue = false, validator = { it.boolean() }),
            AuroraConfigFieldHandler("splunkIndex"),
            AuroraConfigFieldHandler("debug", defaultValue = false, validator = { it.boolean() }),
        )
            .addIfNotNull(gavHandlers(header, cmd))
            .addIfNotNull(templateSpecificHeaders)
            .addIfNotNull(cmd.nodeSelectorHandlers())
    }

    override fun modify(
        adc: AuroraDeploymentSpec,
        resources: Set<AuroraResource>,
        context: FeatureContext
    ) {
        val dcLabels = createDcLabels(adc).normalizeLabels()
        val envVars = createEnvVars(adc).toEnvVars()
        resources.forEach {
            if (it.resource.kind == "ApplicationDeployment") {
                modifyResource(it, "Added information from deployment")
                val ad: ApplicationDeployment = it.resource as ApplicationDeployment
                val spec = ad.spec
                spec.splunkIndex = adc.splunkIndex
                spec.releaseTo = adc.releaseTo
                spec.deployTag = adc.version
                spec.managementPath = adc.managementPath
            } else if (it.resource.kind == "DeploymentConfig") {
                val dc: DeploymentConfig = it.resource as DeploymentConfig

                modifyResource(it, "Added labels, annotations")
                if (dc.spec.template.metadata == null) {
                    dc.spec.template.metadata = ObjectMeta()
                }

                dc.spec.template.metadata.labels = dc.spec.template.metadata.labels?.addIfNotNull(dcLabels) ?: dcLabels
                dc.spec.template.metadata.annotations = mutableMapOf(
                    ANNOTATION_BOOBER_DEPLOYTAG to adc.version
                )
                dc.metadata.labels = it.resource.metadata.labels?.addIfNotNull(dcLabels) ?: dcLabels
                dc.setTopologySpreadConstraints(adc.name)

                if (adc.pause) {
                    dc.spec.replicas = 0
                }

                adc.nodeSelector?.let { nodeSelector ->
                    dc.spec.template.spec.nodeSelector = dc.spec.template.spec.nodeSelector?.addIfNotNull(nodeSelector) ?: nodeSelector
                }
            } else if (it.resource.kind == "Deployment") {
                val deployment: Deployment = it.resource as Deployment

                modifyResource(it, "Added labels, annotations")
                if (deployment.spec.template.metadata == null) {
                    deployment.spec.template.metadata = ObjectMeta()
                }

                deployment.spec.template.metadata.labels =
                    deployment.spec.template.metadata.labels.addIfNotNull(dcLabels)
                deployment.spec.template.metadata.annotations = mapOf(
                    ANNOTATION_BOOBER_DEPLOYTAG to adc.version
                )
                deployment.metadata.labels = it.resource.metadata.labels?.addIfNotNull(dcLabels) ?: dcLabels

                if (adc.pause) {
                    deployment.spec.replicas = 0
                }
            }

            it.resource.allNonSideCarContainers.forEach { container ->

                modifyResource(it, "Added Shared env vars and request limits")
                container.env.addAll(envVars)
                container.resources {
                    val existingRequest = requests ?: emptyMap()
                    val existingLimit = limits ?: emptyMap()
                    requests = existingRequest.addIfNotNull(
                        mapOf(
                            adc.quantity("cpu", "min"),
                            adc.quantity("memory", "min")
                        ).filterNullValues()
                    )
                    limits = existingLimit.addIfNotNull(
                        mapOf(
                            adc.quantity("cpu", "max"),
                            adc.quantity("memory", "max")
                        ).filterNullValues()
                    )
                }
            }
        }
    }

    fun createEnvVars(adc: AuroraDeploymentSpec): Map<String, String> {

        val headerEnv = if (adc.type == TemplateType.deploy) {
            mapOf(
                "AURORA_KLIENTID" to createAuroraKlientId(adc)
            )
        } else null

        val debugEnv = if (adc["debug"]) {
            mapOf(
                "ENABLE_REMOTE_DEBUG" to "true",
                "DEBUG_PORT" to "5005"
            )
        } else null

        return mapOf(
            "OPENSHIFT_CLUSTER" to adc["cluster"],
            "APP_NAME" to adc.name,
            "SPLUNK_INDEX" to adc.splunkIndex,
        ).addIfNotNull(debugEnv).addIfNotNull(headerEnv).filterNullValues()
    }

    fun createDcLabels(adc: AuroraDeploymentSpec): Map<String, String> {

        val pauseLabel = if (adc.pause) {
            "paused" to "true"
        } else null

        return mapOf(
            "deployTag" to adc.version,
            "app.kubernetes.io/version" to adc.version
        ).addIfNotNull(pauseLabel).normalizeLabels()
    }

    private fun createAuroraKlientId(adc: AuroraDeploymentSpec): String {
        val segment: String? = adc.getOrNull("segment")
        // APP_VERSION is available for all images created by Architect
        return "${segment ?: adc.affiliation}/${adc.artifactId}/\${APP_VERSION}"
    }

    fun DeploymentConfig.setTopologySpreadConstraints(appName: String) {
        this.spec.template.spec.topologySpreadConstraints.addAll(
            listOf(
                topologySpreadConstraint(appName, "topology.kubernetes.io/region"),
                topologySpreadConstraint(appName, "topology.kubernetes.io/zone")
            )
        )
    }

    private fun topologySpreadConstraint(appName: String, topologyKey: String): TopologySpreadConstraint =
        TopologySpreadConstraintBuilder()
            .withLabelSelector(LabelSelectorBuilder().addToMatchLabels("name", appName).build())
            .withMaxSkew(1)
            .withTopologyKey(topologyKey)
            .withWhenUnsatisfiable("ScheduleAnyway")
            .build()
}
