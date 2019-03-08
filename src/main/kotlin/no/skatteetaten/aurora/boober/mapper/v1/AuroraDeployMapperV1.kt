package no.skatteetaten.aurora.boober.mapper.v1

import io.micrometer.spring.autoconfigure.export.StringToDurationConverter
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraConfigFileType
import no.skatteetaten.aurora.boober.model.AuroraDeploy
import no.skatteetaten.aurora.boober.model.AuroraDeployStrategy
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfigFlags
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfigResource
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfigResources
import no.skatteetaten.aurora.boober.model.HttpEndpoint
import no.skatteetaten.aurora.boober.model.Probe
import no.skatteetaten.aurora.boober.model.ToxiProxy
import no.skatteetaten.aurora.boober.utils.durationString
import no.skatteetaten.aurora.boober.utils.ensureStartWith
import no.skatteetaten.aurora.boober.utils.length
import no.skatteetaten.aurora.boober.utils.oneOf
import no.skatteetaten.aurora.boober.utils.pattern
import no.skatteetaten.aurora.boober.utils.removeExtension

class AuroraDeployMapperV1(
    val applicationDeploymentRef: ApplicationDeploymentRef,
    val applicationFiles: List<AuroraConfigFile>
) {

    val handlers = listOf(

        AuroraConfigFieldHandler("artifactId",
            defaultValue = applicationFiles.find { it.type == AuroraConfigFileType.BASE }
                ?.let { it.name.removeExtension() }
                ?: applicationDeploymentRef.application,
            defaultSource = "fileName",
            validator = { it.length(50, "ArtifactId must be set and be shorter then 50 characters", false) }),

        AuroraConfigFieldHandler(
            "groupId",
            validator = { it.length(200, "GroupId must be set and be shorter then 200 characters") }),
        AuroraConfigFieldHandler("version", validator = {
            it.pattern(
                "^[\\w][\\w.-]{0,127}$",
                "Version must be a 128 characters or less, alphanumeric and can contain dots and dashes"
            )
        }),
        AuroraConfigFieldHandler("releaseTo"),
        AuroraConfigFieldHandler(
            "deployStrategy/type",
            defaultValue = "rolling",
            validator = { it.oneOf(listOf("recreate", "rolling")) }),
        AuroraConfigFieldHandler("deployStrategy/timeout", defaultValue = 180),
        AuroraConfigFieldHandler("resources/cpu/min", defaultValue = "10m"),
        AuroraConfigFieldHandler("resources/cpu/max", defaultValue = "2000m"),
        AuroraConfigFieldHandler("resources/memory/min", defaultValue = "128Mi"),
        AuroraConfigFieldHandler("resources/memory/max", defaultValue = "512Mi"),
        AuroraConfigFieldHandler("replicas", defaultValue = 1),
        AuroraConfigFieldHandler("serviceAccount"),
        AuroraConfigFieldHandler("prometheus", defaultValue = true, canBeSimplifiedConfig = true),
        AuroraConfigFieldHandler("prometheus/path", defaultValue = "/prometheus"),
        AuroraConfigFieldHandler("prometheus/port", defaultValue = 8081),
        AuroraConfigFieldHandler("management", defaultValue = true, canBeSimplifiedConfig = true),
        AuroraConfigFieldHandler("management/path", defaultValue = "actuator"),
        AuroraConfigFieldHandler("management/port", defaultValue = "8081"),
        AuroraConfigFieldHandler("readiness", defaultValue = true, canBeSimplifiedConfig = true),
        AuroraConfigFieldHandler("readiness/port", defaultValue = 8080),
        AuroraConfigFieldHandler("readiness/path"),
        AuroraConfigFieldHandler("readiness/delay", defaultValue = 10),
        AuroraConfigFieldHandler("readiness/timeout", defaultValue = 1),
        AuroraConfigFieldHandler("liveness", defaultValue = false, canBeSimplifiedConfig = true),
        AuroraConfigFieldHandler("liveness/port", defaultValue = 8080),
        AuroraConfigFieldHandler("liveness/path"),
        AuroraConfigFieldHandler("liveness/delay", defaultValue = 10),
        AuroraConfigFieldHandler("liveness/timeout", defaultValue = 1),
        AuroraConfigFieldHandler("debug", defaultValue = false),
        AuroraConfigFieldHandler("pause", defaultValue = false),
        AuroraConfigFieldHandler("alarm", defaultValue = true),
        AuroraConfigFieldHandler("ttl", validator = { it.durationString() }),
        AuroraConfigFieldHandler("toxiproxy", defaultValue = false, canBeSimplifiedConfig = true),
        AuroraConfigFieldHandler("toxiproxy/version", defaultValue = "2.1.3")
    )

    fun deploy(auroraDeploymentSpec: AuroraDeploymentSpec): AuroraDeploy? {
        val groupId: String = auroraDeploymentSpec["groupId"]

        val version: String = auroraDeploymentSpec["version"]

        val releaseTo: String? = auroraDeploymentSpec.getOrNull<String>("releaseTo")?.takeUnless { it.isEmpty() }

        val artifactId: String = auroraDeploymentSpec["artifactId"]

        val dockerGroup = groupId.replace(".", "_")

        val tag = releaseTo ?: version
        val applicationFile = getApplicationFile(applicationDeploymentRef)

        val pause: Boolean = auroraDeploymentSpec["pause"]
        val replicas: Int = auroraDeploymentSpec["replicas"]

        return AuroraDeploy(
            applicationFile = applicationFile.name,
            releaseTo = releaseTo,
            dockerImagePath = "$dockerGroup/$artifactId",
            dockerTag = tag,
            deployStrategy = AuroraDeployStrategy(
                auroraDeploymentSpec["deployStrategy/type"],
                auroraDeploymentSpec["deployStrategy/timeout"]
            ),
            flags = AuroraDeploymentConfigFlags(
                auroraDeploymentSpec["debug"],
                auroraDeploymentSpec["alarm"],
                pause

            ),
            resources = AuroraDeploymentConfigResources(
                request = AuroraDeploymentConfigResource(
                    cpu = auroraDeploymentSpec["resources/cpu/min"],
                    memory = auroraDeploymentSpec["resources/memory/min"]
                ),
                limit = AuroraDeploymentConfigResource(
                    cpu = auroraDeploymentSpec["resources/cpu/max"],
                    memory = auroraDeploymentSpec["resources/memory/max"]
                )
            ),
            replicas = if (pause) 0 else replicas,
            groupId = groupId,
            artifactId = artifactId,
            version = version,
            serviceAccount = auroraDeploymentSpec.getOrNull("serviceAccount"),
            prometheus = findPrometheus(auroraDeploymentSpec),
            managementPath = findManagementPath(auroraDeploymentSpec),
            liveness = getProbe(auroraDeploymentSpec, "liveness"),
            readiness = getProbe(auroraDeploymentSpec, "readiness"),
            ttl = auroraDeploymentSpec.getOrNull<String>("ttl")
                ?.let { StringToDurationConverter().convert(it) },
            toxiProxy = getToxiProxy(auroraDeploymentSpec, "toxiproxy"),
            pause = pause
        )
    }

    private fun getApplicationFile(applicationDeploymentRef: ApplicationDeploymentRef): AuroraConfigFile {
        val fileName = "${applicationDeploymentRef.environment}/${applicationDeploymentRef.application}"
        val file = applicationFiles.find { it.name.removeExtension() == fileName && !it.override }
        return file ?: throw IllegalArgumentException("Should find applicationFile $fileName")
    }

    private fun findPrometheus(auroraDeploymentSpec: AuroraDeploymentSpec): HttpEndpoint? {
        return auroraDeploymentSpec.featureEnabled("prometheus") {
            HttpEndpoint(auroraDeploymentSpec["$it/path"], auroraDeploymentSpec.getOrNull("$it/port"))
        }
    }

    private fun findManagementPath(auroraDeploymentSpec: AuroraDeploymentSpec): String? {
        return auroraDeploymentSpec.featureEnabled("management") {
            val path = auroraDeploymentSpec.get<String>("$it/path").ensureStartWith("/")
            val port = auroraDeploymentSpec.get<Int>("$it/port").toString().ensureStartWith(":")
            "$port$path"
        }
    }

    fun getProbe(auroraDeploymentSpec: AuroraDeploymentSpec, name: String): Probe? {

        return auroraDeploymentSpec.featureEnabled(name) { field ->
            Probe(
                auroraDeploymentSpec.getOrNull<String?>("$field/path")?.let {
                    if (!it.startsWith("/")) {
                        "/$it"
                    } else it
                },
                auroraDeploymentSpec["$field/port"],
                auroraDeploymentSpec["$field/delay"],
                auroraDeploymentSpec["$field/timeout"]
            )
        }
    }

    fun getToxiProxy(auroraDeploymentSpec: AuroraDeploymentSpec, name: String): ToxiProxy? {
        return auroraDeploymentSpec.featureEnabled(name) {
            ToxiProxy(auroraDeploymentSpec["$it/version"])
        }
    }
}