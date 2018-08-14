package no.skatteetaten.aurora.boober.mapper.v1

import io.micrometer.spring.autoconfigure.export.StringToDurationConverter
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
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
import no.skatteetaten.aurora.boober.utils.notBlank
import no.skatteetaten.aurora.boober.utils.oneOf
import no.skatteetaten.aurora.boober.utils.removeExtension

class AuroraDeployMapperV1(val applicationId: ApplicationId, val applicationFiles: List<AuroraConfigFile>) {

    val configHandlers = applicationFiles.findConfigFieldHandlers()
    val handlers = listOf(

        AuroraConfigFieldHandler("artifactId",
            defaultValue = applicationId.application,
            defaultSource = "fileName",
            validator = { it.length(50, "ArtifactId must be set and be shorter then 50 characters", false) }),
        AuroraConfigFieldHandler("groupId", validator = { it.length(200, "GroupId must be set and be shorter then 200 characters") }),
        AuroraConfigFieldHandler("version", validator = { it.notBlank("Version must be set") }),
        AuroraConfigFieldHandler("releaseTo"),
        AuroraConfigFieldHandler("deployStrategy/type", defaultValue = "rolling", validator = { it.oneOf(listOf("recreate", "rolling")) }),
        AuroraConfigFieldHandler("deployStrategy/timeout", defaultValue = 180),
        AuroraConfigFieldHandler("resources/cpu/min", defaultValue = "100m"),
        AuroraConfigFieldHandler("resources/cpu/max", defaultValue = "2000m"),
        AuroraConfigFieldHandler("resources/memory/min", defaultValue = "128Mi"),
        AuroraConfigFieldHandler("resources/memory/max", defaultValue = "512Mi"),
        AuroraConfigFieldHandler("replicas", defaultValue = 1),
        AuroraConfigFieldHandler("serviceAccount"),
        AuroraConfigFieldHandler("prometheus", defaultValue = true, subKeyFlag = true),
        AuroraConfigFieldHandler("prometheus/path", defaultValue = "/prometheus"),
        AuroraConfigFieldHandler("prometheus/port", defaultValue = 8081),
        AuroraConfigFieldHandler("management", defaultValue = true, subKeyFlag = true),
        AuroraConfigFieldHandler("management/path", defaultValue = "actuator"),
        AuroraConfigFieldHandler("management/port", defaultValue = "8081"),
        AuroraConfigFieldHandler("readiness", defaultValue = true, subKeyFlag = true),
        AuroraConfigFieldHandler("readiness/port", defaultValue = 8080),
        AuroraConfigFieldHandler("readiness/path"),
        AuroraConfigFieldHandler("readiness/delay", defaultValue = 10),
        AuroraConfigFieldHandler("readiness/timeout", defaultValue = 1),
        AuroraConfigFieldHandler("liveness", defaultValue = false, subKeyFlag = true),
        AuroraConfigFieldHandler("liveness/port", defaultValue = 8080),
        AuroraConfigFieldHandler("liveness/path"),
        AuroraConfigFieldHandler("liveness/delay", defaultValue = 10),
        AuroraConfigFieldHandler("liveness/timeout", defaultValue = 1),
        AuroraConfigFieldHandler("debug", defaultValue = false),
        AuroraConfigFieldHandler("pause", defaultValue = false),
        AuroraConfigFieldHandler("alarm", defaultValue = true),
        AuroraConfigFieldHandler("ttl", validator = { it.durationString() }),
        AuroraConfigFieldHandler("toxiproxy", defaultValue = false, subKeyFlag = true),
        AuroraConfigFieldHandler("toxiproxy/version", defaultValue = "2.1.3")
    ) + configHandlers

    fun deploy(auroraDeploymentSpec: AuroraDeploymentSpec): AuroraDeploy? {
        val groupId: String = auroraDeploymentSpec.extract("groupId")

        val version: String = auroraDeploymentSpec.extract("version")

        val releaseTo: String? = auroraDeploymentSpec.extractOrNull<String>("releaseTo")?.takeUnless { it.isEmpty() }

        val artifactId: String = auroraDeploymentSpec.extract("artifactId")

        val dockerGroup = groupId.replace(".", "_")

        val tag = releaseTo ?: version
        val applicationFile = getApplicationFile(applicationId)

        val pause: Boolean = auroraDeploymentSpec.extract("pause")
        val replicas: Int = auroraDeploymentSpec.extract("replicas")

        return AuroraDeploy(
            applicationFile = applicationFile.name,
            releaseTo = releaseTo,
            dockerImagePath = "$dockerGroup/$artifactId",
            dockerTag = tag,
            deployStrategy = AuroraDeployStrategy(
                auroraDeploymentSpec.extract("deployStrategy/type"),
                auroraDeploymentSpec.extract("deployStrategy/timeout")
            ),
            flags = AuroraDeploymentConfigFlags(
                auroraDeploymentSpec.extract("debug"),
                auroraDeploymentSpec.extract("alarm"),
                pause

            ),
            resources = AuroraDeploymentConfigResources(
                request = AuroraDeploymentConfigResource(
                    cpu = auroraDeploymentSpec.extract("resources/cpu/min"),
                    memory = auroraDeploymentSpec.extract("resources/memory/min")
                ),
                limit = AuroraDeploymentConfigResource(
                    cpu = auroraDeploymentSpec.extract("resources/cpu/max"),
                    memory = auroraDeploymentSpec.extract("resources/memory/max")
                )
            ),
            replicas = if (pause) 0 else replicas,
            groupId = groupId,
            artifactId = artifactId,
            version = version,
            serviceAccount = auroraDeploymentSpec.extractOrNull("serviceAccount"),
            prometheus = findPrometheus(auroraDeploymentSpec),
            managementPath = findManagementPath(auroraDeploymentSpec),
            liveness = getProbe(auroraDeploymentSpec, "liveness"),
            readiness = getProbe(auroraDeploymentSpec, "readiness"),
            env = auroraDeploymentSpec.getConfigEnv(configHandlers),
            ttl = auroraDeploymentSpec.extractOrNull<String>("ttl")
                ?.let { StringToDurationConverter().convert(it) },
            toxiProxy = getToxiProxy(auroraDeploymentSpec, "toxiproxy")

        )
    }

    private fun getApplicationFile(applicationId: ApplicationId): AuroraConfigFile {
        val fileName = "${applicationId.environment}/${applicationId.application}"
        val file = applicationFiles.find { it.name.removeExtension() == fileName && !it.override }
        return file ?: throw IllegalArgumentException("Should find applicationFile $fileName")
    }

    private fun findPrometheus(auroraDeploymentSpec: AuroraDeploymentSpec): HttpEndpoint? {

        val name = "prometheus"

        if (auroraDeploymentSpec.disabledAndNoSubKeys(name)) {
            return null
        }
        return HttpEndpoint(
            auroraDeploymentSpec.extract("$name/path"),
            auroraDeploymentSpec.extractOrNull("$name/port")
        )
    }

    private fun findManagementPath(auroraDeploymentSpec: AuroraDeploymentSpec): String? {

        val name = "management"

        if (auroraDeploymentSpec.disabledAndNoSubKeys(name)) {
            return null
        }

        val path = auroraDeploymentSpec.extract<String>("$name/path")
            .ensureStartWith("/")
        val port = auroraDeploymentSpec.extract<String>("$name/port")
            .toString()
            .ensureStartWith(":")
        return "$port$path"
    }

    fun getProbe(auroraDeploymentSpec: AuroraDeploymentSpec, name: String): Probe? {

        if (auroraDeploymentSpec.disabledAndNoSubKeys(name)) {
            return null
        }

        return Probe(
            auroraDeploymentSpec.extractOrNull<String?>("$name/path")?.let {
                if (!it.startsWith("/")) {
                    "/$it"
                } else it
            },
            auroraDeploymentSpec.extract("$name/port"),
            auroraDeploymentSpec.extract("$name/delay"),
            auroraDeploymentSpec.extract("$name/timeout")
        )
    }

    fun getToxiProxy(auroraDeploymentSpec: AuroraDeploymentSpec, name: String): ToxiProxy? {
        if (auroraDeploymentSpec.disabledAndNoSubKeys(name)) {
            return null
        }

        return ToxiProxy(auroraDeploymentSpec.extract("$name/version"))
    }
}