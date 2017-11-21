package no.skatteetaten.aurora.boober.mapper.v1

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.ApplicationPlatform
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeploy
import no.skatteetaten.aurora.boober.model.AuroraDeployStrategy
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfigFlags
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfigResource
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfigResources
import no.skatteetaten.aurora.boober.model.Database
import no.skatteetaten.aurora.boober.model.HttpEndpoint
import no.skatteetaten.aurora.boober.model.Probe
import no.skatteetaten.aurora.boober.model.Webseal
import no.skatteetaten.aurora.boober.model.findConfigFieldHandlers
import no.skatteetaten.aurora.boober.model.findSubKeys
import no.skatteetaten.aurora.boober.utils.ensureStartWith
import no.skatteetaten.aurora.boober.utils.length
import no.skatteetaten.aurora.boober.utils.notBlank
import no.skatteetaten.aurora.boober.utils.oneOf

class AuroraDeployMapperV1(val applicationId: ApplicationId, val applicationFiles: List<AuroraConfigFile>, val overrideFiles: List<AuroraConfigFile>, val dockerRegistry: String) {

    val dbHandlers = findDbHandlers(applicationFiles)

    val configHandlers = applicationFiles.findConfigFieldHandlers()
    val handlers = dbHandlers + listOf(

            AuroraConfigFieldHandler("artifactId",
                    defaultValue = applicationId.application,
                    defaultSource = "fileName",
                    validator = { it.length(50, "ArtifactId must be set and be shorter then 50 characters", false) }),
            AuroraConfigFieldHandler("groupId", validator = { it.length(200, "GroupId must be set and be shorter then 200 characters") }),
            AuroraConfigFieldHandler("version", validator = { it.notBlank("Version must be set as string") }),
            AuroraConfigFieldHandler("releaseTo"),
            AuroraConfigFieldHandler("applicationPlatform", defaultValue = ApplicationPlatform.java, validator = { it.oneOf(ApplicationPlatform.values().map { it.name }) }),
            AuroraConfigFieldHandler("deployStrategy/type", defaultValue = "rolling", validator = { it.oneOf(listOf("recreate", "rolling")) }),
            AuroraConfigFieldHandler("deployStrategy/timeout", defaultValue = 180),
            AuroraConfigFieldHandler("database", defaultValue = false),
            AuroraConfigFieldHandler("resources/cpu/min", defaultValue = "100m"),
            AuroraConfigFieldHandler("resources/cpu/max", defaultValue = "2000m"),
            AuroraConfigFieldHandler("resources/memory/min", defaultValue = "128Mi"),
            AuroraConfigFieldHandler("resources/memory/max", defaultValue = "512Mi"),
            AuroraConfigFieldHandler("replicas", defaultValue = 1),
            AuroraConfigFieldHandler("splunkIndex"),
            AuroraConfigFieldHandler("serviceAccount"),
            AuroraConfigFieldHandler("prometheus", defaultValue = true),
            AuroraConfigFieldHandler("prometheus/path", defaultValue = "/prometheus"),
            AuroraConfigFieldHandler("prometheus/port", defaultValue = 8081),
            AuroraConfigFieldHandler("management", defaultValue = true),
            AuroraConfigFieldHandler("management/path", defaultValue = "actuator"),
            AuroraConfigFieldHandler("management/port", defaultValue = "8081"),
            AuroraConfigFieldHandler("certificate/commonName"),
            AuroraConfigFieldHandler("certificate", defaultValue = false),
            AuroraConfigFieldHandler("readiness", defaultValue = true),
            AuroraConfigFieldHandler("readiness/port", defaultValue = 8080),
            AuroraConfigFieldHandler("readiness/path"),
            AuroraConfigFieldHandler("readiness/delay", defaultValue = 10),
            AuroraConfigFieldHandler("readiness/timeout", defaultValue = 1),
            AuroraConfigFieldHandler("liveness", defaultValue = false),
            AuroraConfigFieldHandler("liveness/port", defaultValue = 8080),
            AuroraConfigFieldHandler("liveness/path"),
            AuroraConfigFieldHandler("liveness/delay", defaultValue = 10),
            AuroraConfigFieldHandler("liveness/timeout", defaultValue = 1),
            AuroraConfigFieldHandler("webseal", defaultValue = false),
            AuroraConfigFieldHandler("webseal/host"),
            AuroraConfigFieldHandler("webseal/roles"),
            AuroraConfigFieldHandler("debug", defaultValue = false),
            AuroraConfigFieldHandler("pause", defaultValue = false),
            AuroraConfigFieldHandler("alarm", defaultValue = true)

    ) + configHandlers

    fun deploy(auroraConfigFields: AuroraConfigFields): AuroraDeploy? {
        val name: String = auroraConfigFields.extract("name")
        val groupId: String = auroraConfigFields.extract("groupId")

        val certificateCn = if (auroraConfigFields.isSimplifiedConfig("certificate")) {
            val certFlag: Boolean = auroraConfigFields.extract("certificate")
            if (certFlag) "$groupId.$name" else null
        } else {
            auroraConfigFields.extractOrNull("certificate/commonName")
        }

        val version: String = auroraConfigFields.extract("version")

        val releaseTo: String? = auroraConfigFields.extractOrNull("releaseTo")

        val artifactId: String = auroraConfigFields.extract("artifactId")

        val dockerGroup = groupId.replace(".", "_")

        val tag = releaseTo ?: version

        val applicationFile = getApplicationFile(applicationId)
        val overrideFiles = overrideFiles.map { it.name to it.contents }.toMap()
        val pause: Boolean = auroraConfigFields.extract("pause")
        val replicas: Int = auroraConfigFields.extract("replicas")

        return AuroraDeploy(
                applicationFile = applicationFile.name,
                releaseTo = releaseTo,
                dockerImagePath = "$dockerRegistry/$dockerGroup/$artifactId",
                dockerTag = tag,
                overrideFiles = overrideFiles,
                deployStrategy = AuroraDeployStrategy(
                        auroraConfigFields.extract("deployStrategy/type"),
                        auroraConfigFields.extract("deployStrategy/timeout")
                ),
                flags = AuroraDeploymentConfigFlags(
                        auroraConfigFields.extract("debug"),
                        auroraConfigFields.extract("alarm"),
                        pause

                ),
                resources = AuroraDeploymentConfigResources(
                        memory = AuroraDeploymentConfigResource(
                                min = auroraConfigFields.extract("resources/memory/min"),
                                max = auroraConfigFields.extract("resources/memory/max")
                        ),
                        cpu = AuroraDeploymentConfigResource(
                                min = auroraConfigFields.extract("resources/cpu/min"),
                                max = auroraConfigFields.extract("resources/cpu/max")
                        )
                ),
                replicas = if (pause) 0 else replicas,
                applicationPlatform = auroraConfigFields.extract("applicationPlatform"),
                groupId = groupId,
                artifactId = artifactId,
                version = version,
                splunkIndex = auroraConfigFields.extractOrNull("splunkIndex"),
                database = findDatabases(auroraConfigFields, name),

                certificateCn = certificateCn,
                serviceAccount = auroraConfigFields.extractOrNull("serviceAccount"),
                webseal = findWebseal(auroraConfigFields),
                prometheus = findPrometheus(auroraConfigFields),
                managementPath = findManagementPath(auroraConfigFields),
                liveness = getProbe(auroraConfigFields, "liveness"),
                readiness = getProbe(auroraConfigFields, "readiness"),
                env = auroraConfigFields.getConfigEnv(configHandlers)
        )
    }

    private fun getApplicationFile(applicationId: ApplicationId): AuroraConfigFile {
        val fileName = "${applicationId.environment}/${applicationId.application}.json"
        val file = applicationFiles.find { it.name == fileName && !it.override }
        return file ?: throw IllegalArgumentException("Should find applicationFile $fileName")
    }


    private fun findWebseal(auroraConfigFields: AuroraConfigFields): Webseal? {

        val name = "webseal"
        if (auroraConfigFields.disabledAndNoSubKeys(name)) {
            return null
        }

        return Webseal(
                auroraConfigFields.extractOrNull("$name/host"),
                auroraConfigFields.extractOrNull("$name/roles")
        )

    }

    private fun findPrometheus(auroraConfigFields: AuroraConfigFields): HttpEndpoint? {

        val name = "prometheus"

        if (auroraConfigFields.disabledAndNoSubKeys(name)) {
            return null
        }
        return HttpEndpoint(
                auroraConfigFields.extract("$name/path"),
                auroraConfigFields.extractOrNull("$name/port")
        )
    }

    private fun findManagementPath(auroraConfigFields: AuroraConfigFields): String? {

        val name = "management"

        if (auroraConfigFields.disabledAndNoSubKeys(name)) {
            return null
        }

        val path = auroraConfigFields.extract<String>("$name/path").ensureStartWith("/")
        val port = auroraConfigFields.extract<String>("$name/port").toString().ensureStartWith(":")
        return "$port$path"
    }

    private fun findDatabases(auroraConfigFields: AuroraConfigFields, name: String): List<Database> {

        val simplified = auroraConfigFields.isSimplifiedConfig("database")

        if (simplified && auroraConfigFields.extract("database")) {
            return listOf(Database(name = name))
        }

        return auroraConfigFields.getDatabases(dbHandlers)
    }


    fun findDbHandlers(applicationFiles: List<AuroraConfigFile>): List<AuroraConfigFieldHandler> {

        val keys = applicationFiles.findSubKeys("database")

        return keys.map { key ->
            AuroraConfigFieldHandler("database/$key")
        }
    }


    fun getProbe(auroraConfigFields: AuroraConfigFields, name: String): Probe? {

        if (auroraConfigFields.disabledAndNoSubKeys(name)) {
            return null
        }

        return Probe(
                auroraConfigFields.extractOrNull<String?>("$name/path")?.let {
                    if (!it.startsWith("/")) {
                        "/$it"
                    } else it
                },
                auroraConfigFields.extract("$name/port"),
                auroraConfigFields.extract("$name/delay"),
                auroraConfigFields.extract("$name/timeout")
        )
    }


}